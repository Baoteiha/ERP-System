package vn.essvn.erpcafe.sales;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.jayway.jsonpath.JsonPath;

import vn.essvn.erpcafe.support.AbstractIntegrationTest;

/**
 * End-to-end POS flow: order → split payment → complete (stock deduction + COGS),
 * void restocks, and cashier RBAC (can sell, cannot refund).
 */
class SalesFlowIntegrationTest extends AbstractIntegrationTest {

    private String token;
    private String branchId;

    /** Globally-unique suffix — all integration tests share one Testcontainers DB, so names must not collide. */
    private static String uniq() {
        return UUID.randomUUID().toString().substring(0, 8);
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

        // Order: 2 x Latte, 10% tax → subtotal 50000, tax 5000, grand 55000
        String orderJson = create("/api/v1/orders",
                "{\"orderType\":\"TAKEAWAY\",\"taxRate\":0.10,\"lines\":[{\"productId\":\"%s\",\"quantity\":2}]}".formatted(productId));
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
                .andExpect(jsonPath("$.status").value("PAID"));

        // Complete → deduct 2×18=36g, capture COGS = 36 × 0.20 = 7.20
        mockMvc.perform(authed(post("/api/v1/orders/" + orderId + "/complete"), token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.cogsTotal").value(7.20));

        assertThat(stockQty(ingredientId)).isEqualByComparingTo("964"); // 1000 - 36
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
        mockMvc.perform(authed(post("/api/v1/orders/" + orderId + "/complete"), token)).andExpect(status().isOk());
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

        // cashier creates + pays + completes (sales:write) → allowed
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
        mockMvc.perform(post("/api/v1/orders/" + orderId + "/complete")
                        .header(HttpHeaders.AUTHORIZATION, authHeader(cashierToken)).header("X-Branch-Id", branchId))
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
