package vn.essvn.erpcafe.reporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.jayway.jsonpath.JsonPath;

import vn.essvn.erpcafe.support.AbstractIntegrationTest;

/**
 * Class: ReportFiguresIntegrationTest
 * Purpose: Proves report figures add up (net sales, margins, Vietnam-time days, period limits, caller-only outlets).
 */
class ReportFiguresIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    private String token;
    private List<String> branchIds;

    private static String uniq() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private String adminPost(String url, String body) throws Exception {
        return mockMvc.perform(post(url).header(HttpHeaders.AUTHORIZATION, authHeader(adminToken()))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is2xxSuccessful())
                .andReturn().getResponse().getContentAsString();
    }

    /**
     * A user who only has the given fresh branches, so every figure over them comes from this test's orders
     * alone; the shared admin and its branch grants are left untouched.
     */
    private void freshTenant(int branchCount, String taxRate) throws Exception {
        String s = uniq();
        branchIds = new ArrayList<>();
        for (int i = 0; i < branchCount; i++) {
            branchIds.add(JsonPath.read(adminPost("/api/v1/branches",
                    "{\"name\":\"Rpt %s-%d\",\"code\":\"R%s%d\",\"taxRate\":%s}".formatted(s, i, s, i, taxRate)), "$.id"));
        }
        String roleId = JsonPath.read(adminPost("/api/v1/roles",
                ("{\"name\":\"Rpt %s\",\"description\":\"d\",\"permissions\":[\"catalog:read\",\"catalog:write\","
                        + "\"inventory:read\",\"inventory:write\",\"purchasing:read\",\"purchasing:write\","
                        + "\"sales:read\",\"sales:write\",\"report:read\"]}").formatted(s)), "$.id");
        String userId = JsonPath.read(adminPost("/api/v1/users",
                "{\"email\":\"rpt%s@a.test\",\"password\":\"password123\",\"fullName\":\"Rpt\"}".formatted(s)), "$.id");
        // Only the first branch is granted: the outlet test relies on the second one being out of reach.
        adminPost("/api/v1/users/" + userId + "/access",
                "{\"branchId\":\"%s\",\"roleId\":\"%s\"}".formatted(branchIds.get(0), roleId));
        token = accessToken("rpt%s@a.test".formatted(s), "password123");
    }

    private MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder b) {
        return b.header(HttpHeaders.AUTHORIZATION, authHeader(token)).header("X-Branch-Id", branchIds.get(0));
    }

    private String create(String url, String body) throws Exception {
        return mockMvc.perform(authed(post(url)).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is2xxSuccessful())
                .andReturn().getResponse().getContentAsString();
    }

    /** Stock of 1000 g costing 100 each, and a 25,000 drink whose recipe uses 50 g. Returns the product id. */
    private String sellableProduct() throws Exception {
        String s = uniq();
        String ingredientId = JsonPath.read(create("/api/v1/ingredients",
                "{\"name\":\"Bean-%s\",\"baseUnit\":\"g\"}".formatted(s)), "$.id");
        String supplierId = JsonPath.read(create("/api/v1/suppliers", "{\"name\":\"Roaster-%s\"}".formatted(s)), "$.id");
        String poJson = create("/api/v1/purchase-orders",
                "{\"supplierId\":\"%s\",\"lines\":[{\"ingredientId\":\"%s\",\"orderedQty\":1000,\"unitCost\":100}]}"
                        .formatted(supplierId, ingredientId));
        String poId = JsonPath.read(poJson, "$.id");
        String lineId = JsonPath.read(poJson, "$.lines[0].id");
        mockMvc.perform(authed(post("/api/v1/purchase-orders/" + poId + "/send"))).andExpect(status().isOk());
        mockMvc.perform(authed(post("/api/v1/purchase-orders/" + poId + "/receive"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"receipts\":[{\"lineId\":\"%s\",\"receivedQty\":1000}]}".formatted(lineId)))
                .andExpect(status().isOk());
        String categoryId = JsonPath.read(create("/api/v1/categories",
                "{\"name\":\"Drinks-%s\",\"displayOrder\":1}".formatted(s)), "$.id");
        String productId = JsonPath.read(create("/api/v1/products",
                "{\"categoryId\":\"%s\",\"name\":\"Latte-%s\",\"sku\":\"LAT-%s\",\"basePrice\":25000}"
                        .formatted(categoryId, s, s)), "$.id");
        mockMvc.perform(authed(put("/api/v1/products/" + productId + "/recipe"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lines\":[{\"ingredientId\":\"%s\",\"quantity\":50,\"unit\":\"g\"}]}"
                                .formatted(ingredientId)))
                .andExpect(status().isOk());
        return productId;
    }

    /** Creates and fully pays an order; returns its id. */
    private String paidOrder(String body, int amount) throws Exception {
        String orderId = JsonPath.read(create("/api/v1/orders", body), "$.id");
        mockMvc.perform(authed(post("/api/v1/orders/" + orderId + "/payments"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"method\":\"CASH\",\"amount\":%d}".formatted(amount)))
                .andExpect(status().isOk());
        return orderId;
    }

    private BigDecimal num(String json, String path) {
        return new BigDecimal(String.valueOf((Object) JsonPath.read(json, path)));
    }

    private String report(String path, Instant from, Instant to) throws Exception {
        return mockMvc.perform(authed(get("/api/v1/reports/" + path)
                        .param("from", from.toString()).param("to", to.toString())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void summary_marginsAreComputedOnNetSalesAndOneCogsFigure() throws Exception {
        freshTenant(1, "0.10");
        String productId = sellableProduct();
        paidOrder(("{\"orderType\":\"DINE_IN\",\"orderDiscount\":3000,"
                + "\"lines\":[{\"productId\":\"%s\",\"quantity\":2,\"lineDiscount\":5000}]}").formatted(productId), 46200);

        Instant now = Instant.now();
        String json = report("summary", now.minus(1, ChronoUnit.HOURS), now.plus(1, ChronoUnit.HOURS));

        assertThat(num(json, "$.grossSales")).isEqualByComparingTo("50000");
        assertThat(num(json, "$.discountTotal")).isEqualByComparingTo("8000");
        assertThat(num(json, "$.netSales")).isEqualByComparingTo("42000");
        assertThat(num(json, "$.taxTotal")).isEqualByComparingTo("4200");
        assertThat(num(json, "$.grandTotal")).isEqualByComparingTo("46200");
        assertThat(num(json, "$.cogs")).isEqualByComparingTo("10000");
        assertThat(num(json, "$.grossMargin")).isEqualByComparingTo("32000");
        assertThat(num(json, "$.netMargin")).isEqualByComparingTo("32000");
        mockMvc.perform(authed(get("/api/v1/reports/summary")
                        .param("from", now.minus(1, ChronoUnit.HOURS).toString())
                        .param("to", now.plus(1, ChronoUnit.HOURS).toString())))
                .andExpect(jsonPath("$.cogsOrders").doesNotExist())
                .andExpect(jsonPath("$.revenue").doesNotExist());
    }

    @Test
    void salesByDay_groupsInVietnamTime() throws Exception {
        freshTenant(1, "0.10");
        String productId = sellableProduct();
        // gross 25,000, discount 5,000, net 20,000, tax 2,000, grand 22,000: the day figure must be the net.
        String orderId = paidOrder(
                "{\"orderType\":\"DINE_IN\",\"lines\":[{\"productId\":\"%s\",\"quantity\":1,\"lineDiscount\":5000}]}"
                        .formatted(productId), 22000);
        // 17:30 UTC on 2 March is 00:30 on 3 March in Vietnam.
        jdbc.update("update sales_order set created_at = ?::timestamptz where id = ?::uuid",
                "2026-03-02T17:30:00Z", orderId);

        String json = report("sales-by-day", Instant.parse("2026-03-01T00:00:00Z"), Instant.parse("2026-03-05T00:00:00Z"));

        List<String> days = JsonPath.read(json, "$[*].day");
        assertThat(days).containsExactly("2026-03-03");
        assertThat(num(json, "$[0].netSales")).isEqualByComparingTo("20000");
    }

    @Test
    void fromAfterTo_isRejectedWith400() throws Exception {
        freshTenant(1, "0");
        Instant now = Instant.now();
        mockMvc.perform(authed(get("/api/v1/reports/summary")
                        .param("from", now.toString()).param("to", now.minus(1, ChronoUnit.DAYS).toString())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }

    @Test
    void rangeLongerThanTheLimit_isRejectedWith400() throws Exception {
        freshTenant(1, "0");
        Instant now = Instant.now();
        mockMvc.perform(authed(get("/api/v1/reports/sales-by-day")
                        .param("from", now.minus(400, ChronoUnit.DAYS).toString()).param("to", now.toString())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }

    @Test
    void outlets_listsOnlyTheBranchesTheCallerMayAccess() throws Exception {
        freshTenant(2, "0");
        Instant now = Instant.now();
        String json = report("outlets", now.minus(1, ChronoUnit.HOURS), now.plus(1, ChronoUnit.HOURS));

        List<String> ids = JsonPath.read(json, "$[*].branchId");
        assertThat(ids).containsExactly(branchIds.get(0));
    }
}
