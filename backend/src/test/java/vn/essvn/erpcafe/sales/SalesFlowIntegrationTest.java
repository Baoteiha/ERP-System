package vn.essvn.erpcafe.sales;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.jayway.jsonpath.JsonPath;

import vn.essvn.erpcafe.common.context.TenantContext;
import vn.essvn.erpcafe.organization.domain.Branch;
import vn.essvn.erpcafe.organization.persistence.BranchRepository;
import vn.essvn.erpcafe.sales.api.OrderCompleted;
import vn.essvn.erpcafe.sales.api.OrderReversed;
import vn.essvn.erpcafe.support.AbstractIntegrationTest;

/**
 * End-to-end POS flow: order → split payment → completes on full payment (stock deduction + COGS),
 * void restocks, and cashier RBAC (can sell, cannot refund).
 */
class SalesFlowIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private BranchRepository branches;

    @Autowired
    @Qualifier("ownerDataSource")
    private DataSource ownerDataSource;

    private String token;
    private String branchId;

    /** Globally-unique suffix — all integration tests share one Testcontainers DB, so names must not collide. */
    private static String uniq() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    /** The seeded admin branch is shared by every test class, so a rate set here is put back afterwards. */
    private void setBranchTaxRate(String rate) {
        TenantContext.runAsSystem(() -> {
            Branch branch = branches.findById(UUID.fromString(branchId)).orElseThrow();
            branch.setTaxRate(new BigDecimal(rate));
            branches.save(branch);
        });
    }

    @AfterEach
    void restoreBranchTaxRate() {
        if (branchId != null) {
            setBranchTaxRate("0");
        }
    }

    private MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder b, String tok) {
        return b.header(HttpHeaders.AUTHORIZATION, authHeader(tok)).header("X-Branch-Id", branchId);
    }

    private String create(String url, String body) throws Exception {
        return mockMvc.perform(authed(post(url), token).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is2xxSuccessful())
                .andReturn().getResponse().getContentAsString();
    }

    /** Builds branch + coffee ingredient (1000g @0.20 in stock) + a Latte (25000, recipe 18g). Returns [ingredientId, productId]. */
    private String[] setup(String s) throws Exception {
        token = adminToken();
        branchId = ((List<String>) JsonPath.read(
                mockMvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, authHeader(token)))
                        .andReturn().getResponse().getContentAsString(), "$.branchIds")).get(0);

        String ingredientId = JsonPath.read(create("/api/v1/ingredients",
                "{\"name\":\"Coffee-%s\",\"baseUnit\":\"g\"}".formatted(s)), "$.id");
        String supplierId = JsonPath.read(create("/api/v1/suppliers", "{\"name\":\"Roaster-%s\"}".formatted(s)), "$.id");
        String poJson = create("/api/v1/purchase-orders",
                "{\"supplierId\":\"%s\",\"lines\":[{\"ingredientId\":\"%s\",\"orderedQty\":1000,\"unitCost\":0.20}]}"
                        .formatted(supplierId, ingredientId));
        String poId = JsonPath.read(poJson, "$.id");
        String lineId = JsonPath.read(poJson, "$.lines[0].id");
        mockMvc.perform(authed(post("/api/v1/purchase-orders/" + poId + "/send"), token)).andExpect(status().isOk());
        mockMvc.perform(authed(post("/api/v1/purchase-orders/" + poId + "/receive"), token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"receipts\":[{\"lineId\":\"%s\",\"receivedQty\":1000}]}".formatted(lineId)))
                .andExpect(status().isOk());

        String categoryId = JsonPath.read(create("/api/v1/categories",
                "{\"name\":\"Drinks-%s\",\"displayOrder\":1}".formatted(s)), "$.id");
        String productId = JsonPath.read(create("/api/v1/products",
                "{\"categoryId\":\"%s\",\"name\":\"Latte-%s\",\"sku\":\"LAT-%s\",\"basePrice\":25000}".formatted(categoryId, s, s)),
                "$.id");
        mockMvc.perform(authed(put("/api/v1/products/" + productId + "/recipe"), token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lines\":[{\"ingredientId\":\"%s\",\"quantity\":18,\"unit\":\"g\"}]}".formatted(ingredientId)))
                .andExpect(status().isOk());

        return new String[] { ingredientId, productId };
    }

    /** Reads a numeric JSON field as BigDecimal. The (Object) cast forces String.valueOf(Object)
     *  — otherwise JsonPath.read's generic return type makes javac pick String.valueOf(char[]). */
    private static BigDecimal num(String json, String path) {
        return new BigDecimal(String.valueOf((Object) JsonPath.read(json, path)));
    }

    private BigDecimal stockQty(String ingredientId) throws Exception {
        String json = mockMvc.perform(get("/api/v1/stock").header(HttpHeaders.AUTHORIZATION, authHeader(token))
                        .header("X-Branch-Id", branchId))
                .andReturn().getResponse().getContentAsString();
        List<Object> vals = JsonPath.read(json, "$[?(@.ingredientId=='%s')].quantityOnHand".formatted(ingredientId));
        return new BigDecimal(String.valueOf(vals.get(0)));
    }

    @Test
    void fullOrderFlow_splitPayment_completeDeductsStockAndCapturesCogs() throws Exception {
        String[] ids = setup(uniq());
        String ingredientId = ids[0];
        String productId = ids[1];
        setBranchTaxRate("0.1");

        // Order: 2 x Latte, 10% tax → subtotal 50000, tax 5000, grand 55000
        String orderJson = create("/api/v1/orders",
                "{\"orderType\":\"TAKEAWAY\",\"lines\":[{\"productId\":\"%s\",\"quantity\":2}]}".formatted(productId));
        String orderId = JsonPath.read(orderJson, "$.id");
        assertThat(num(orderJson, "$.grandTotal")).isEqualByComparingTo("55000");
        String status = JsonPath.read(orderJson, "$.status");
        assertThat(status).isEqualTo("OPEN");

        // Split payment: 30000 then 25000 → fully paid
        mockMvc.perform(authed(post("/api/v1/orders/" + orderId + "/payments"), token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"method\":\"CASH\",\"amount\":30000}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPEN")); // not fully paid yet
        mockMvc.perform(authed(post("/api/v1/orders/" + orderId + "/payments"), token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"method\":\"CARD\",\"amount\":25000}"))
                .andExpect(status().isOk())
                // full payment completes: deduct 2×18=36g, capture COGS = 36 × 0.20 = 7.20
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.cogsTotal").value(7.20));

        assertThat(stockQty(ingredientId)).isEqualByComparingTo("964"); // 1000 - 36
    }

    /** The payload of the one event of {@code type} about the order, read past row-level security. */
    private String eventAbout(Class<?> type, String orderId) {
        return new JdbcTemplate(ownerDataSource).queryForObject("SELECT payload::text FROM domain_event"
                + " WHERE event_type = ? AND payload->>'orderId' = ?", String.class, type.getName(), orderId);
    }

    private Instant orderTime(String column, String orderId) {
        return new JdbcTemplate(ownerDataSource).queryForObject("SELECT " + column + " FROM sales_order WHERE id = ?::uuid",
                Timestamp.class, orderId).toInstant();
    }

    @Test
    void completingAndRefundingAnOrder_recordsItsFiguresAsEvents() throws Exception {
        String[] ids = setup(uniq());
        setBranchTaxRate("0.1");
        // gross 50,000, discount 5,000, net 45,000, tax 4,500, grand 49,500; COGS 2 x 18 g x 0.20 = 7.20
        String orderId = JsonPath.read(create("/api/v1/orders", ("{\"orderType\":\"TAKEAWAY\",\"orderDiscount\":5000,"
                + "\"lines\":[{\"productId\":\"%s\",\"quantity\":2}]}").formatted(ids[1])), "$.id");
        mockMvc.perform(authed(post("/api/v1/orders/" + orderId + "/payments"), token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"method\":\"CASH\",\"amount\":49500}"))
                .andExpect(status().isOk());
        mockMvc.perform(authed(post("/api/v1/orders/" + orderId + "/refund"), token)).andExpect(status().isOk());
        String company = new JdbcTemplate(ownerDataSource).queryForObject(
                "SELECT company_id::text FROM branch WHERE id = ?::uuid", String.class, branchId);

        String completed = eventAbout(OrderCompleted.class, orderId);
        assertThat((String) JsonPath.read(completed, "$.companyId")).isEqualTo(company);
        assertThat((String) JsonPath.read(completed, "$.branchId")).isEqualTo(branchId);
        assertThat(Instant.parse(JsonPath.read(completed, "$.occurredAt")))
                .isCloseTo(orderTime("completed_at", orderId), within(1, ChronoUnit.MILLIS));
        assertThat(num(completed, "$.grossSales")).isEqualByComparingTo("50000");
        assertThat(num(completed, "$.discountTotal")).isEqualByComparingTo("5000");
        assertThat(num(completed, "$.netSales")).isEqualByComparingTo("45000");
        assertThat(num(completed, "$.taxTotal")).isEqualByComparingTo("4500");
        assertThat(num(completed, "$.cogs")).isEqualByComparingTo("7.20");
        // a sale completed as it was paid was never booked before
        assertThat(JsonPath.<Map<String, Object>>read(completed, "$").get("previouslyBookedAt")).isNull();

        String reversed = eventAbout(OrderReversed.class, orderId);
        assertThat((String) JsonPath.read(reversed, "$.companyId")).isEqualTo(company);
        assertThat(Instant.parse(JsonPath.read(reversed, "$.occurredAt")))
                .isCloseTo(orderTime("reversed_at", orderId), within(1, ChronoUnit.MILLIS));
        assertThat(num(reversed, "$.netSales")).isEqualByComparingTo("45000");
        assertThat(num(reversed, "$.taxTotal")).isEqualByComparingTo("4500");
        assertThat(num(reversed, "$.cogs")).isEqualByComparingTo("7.20");
    }

    @Test
    void completingALegacyPaidOrder_recordsWhenReportsBookedItBefore() throws Exception {
        String[] ids = setup(uniq());
        String orderId = JsonPath.read(create("/api/v1/orders",
                "{\"orderType\":\"DINE_IN\",\"lines\":[{\"productId\":\"%s\",\"quantity\":1}]}".formatted(ids[1])), "$.id");
        mockMvc.perform(authed(post("/api/v1/orders/" + orderId + "/payments"), token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"method\":\"CASH\",\"amount\":25000}"))
                .andExpect(status().isOk());
        // a pre-migration row: paid, never completed, so reports booked it when it was created
        JdbcTemplate db = new JdbcTemplate(ownerDataSource);
        db.update("DELETE FROM domain_event WHERE payload->>'orderId' = ?", orderId);
        db.update("UPDATE sales_order SET status = 'PAID', completed_at = NULL,"
                + " created_at = '2026-03-02T05:00:00Z' WHERE id = ?::uuid", orderId);

        mockMvc.perform(authed(post("/api/v1/orders/" + orderId + "/complete"), token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));

        String completed = eventAbout(OrderCompleted.class, orderId);
        assertThat(Instant.parse(JsonPath.read(completed, "$.previouslyBookedAt")))
                .isEqualTo(Instant.parse("2026-03-02T05:00:00Z"));
        assertThat(num(completed, "$.netSales")).isEqualByComparingTo("25000");
    }

    @Test
    void voidOrder_restocksIngredients() throws Exception {
        String[] ids = setup(uniq());
        String ingredientId = ids[0];
        String productId = ids[1];

        String orderId = JsonPath.read(create("/api/v1/orders",
                "{\"orderType\":\"DINE_IN\",\"lines\":[{\"productId\":\"%s\",\"quantity\":1}]}".formatted(productId)), "$.id");
        mockMvc.perform(authed(post("/api/v1/orders/" + orderId + "/payments"), token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"method\":\"CASH\",\"amount\":25000}"))
                .andExpect(status().isOk());
        assertThat(stockQty(ingredientId)).isEqualByComparingTo("982"); // 1000 - 18

        // Void → stock returned
        mockMvc.perform(authed(post("/api/v1/orders/" + orderId + "/void"), token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("VOID"));
        assertThat(stockQty(ingredientId)).isEqualByComparingTo("1000"); // restocked
    }

    @Test
    void cashier_canSellButNotRefund() throws Exception {
        String s = uniq();
        String[] ids = setup(s);
        String productId = ids[1];

        // create a cashier and grant CASHIER at the branch
        String cashierUserId = JsonPath.read(create("/api/v1/users",
                "{\"email\":\"pos-cashier-%s@esscafe.vn\",\"password\":\"cashier123\",\"fullName\":\"P\"}".formatted(s)), "$.id");
        String rolesJson = mockMvc.perform(get("/api/v1/roles").header(HttpHeaders.AUTHORIZATION, authHeader(token)))
                .andReturn().getResponse().getContentAsString();
        String cashierRoleId = ((List<String>) JsonPath.read(rolesJson, "$[?(@.name=='CASHIER')].id")).get(0);
        create("/api/v1/users/" + cashierUserId + "/access",
                "{\"branchId\":\"%s\",\"roleId\":\"%s\"}".formatted(branchId, cashierRoleId));

        String cashierToken = accessToken("pos-cashier-%s@esscafe.vn".formatted(s), "cashier123");

        // cashier creates + pays (sales:write) → allowed
        String orderId = JsonPath.read(mockMvc.perform(post("/api/v1/orders")
                        .header(HttpHeaders.AUTHORIZATION, authHeader(cashierToken)).header("X-Branch-Id", branchId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderType\":\"DINE_IN\",\"lines\":[{\"productId\":\"%s\",\"quantity\":1}]}".formatted(productId)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "$.id");
        mockMvc.perform(post("/api/v1/orders/" + orderId + "/payments")
                        .header(HttpHeaders.AUTHORIZATION, authHeader(cashierToken)).header("X-Branch-Id", branchId)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"method\":\"CASH\",\"amount\":25000}"))
                .andExpect(status().isOk());

        // cashier tries to refund (needs sales:refund) → 403
        mockMvc.perform(post("/api/v1/orders/" + orderId + "/refund")
                        .header(HttpHeaders.AUTHORIZATION, authHeader(cashierToken)).header("X-Branch-Id", branchId))
                .andExpect(status().isForbidden());
    }

    @Test
    void createOrder_rejectsMissingRequiredModifierSelection() throws Exception {
        String s = uniq();
        String[] ids = setup(s);
        String productId = ids[1];

        // attach a required "Size" group (pick exactly 1) with no selection → invalid
        String groupId = JsonPath.read(create("/api/v1/modifier-groups",
                "{\"name\":\"Size-%s\",\"minSelect\":1,\"maxSelect\":1}".formatted(s)), "$.id");
        create("/api/v1/modifier-groups/" + groupId + "/modifiers", "{\"name\":\"Large\",\"priceDelta\":5000,\"displayOrder\":1}");
        mockMvc.perform(authed(put("/api/v1/products/" + productId + "/modifier-groups"), token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"modifierGroupIds\":[\"%s\"]}".formatted(groupId)))
                .andExpect(status().isOk());

        mockMvc.perform(authed(post("/api/v1/orders"), token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderType\":\"DINE_IN\",\"lines\":[{\"productId\":\"%s\",\"quantity\":1}]}".formatted(productId)))
                .andExpect(status().is(422));
    }
}
