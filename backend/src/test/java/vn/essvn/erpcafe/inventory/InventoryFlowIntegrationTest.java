package vn.essvn.erpcafe.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.jayway.jsonpath.JsonPath;

import vn.essvn.erpcafe.inventory.api.CogsResult;
import vn.essvn.erpcafe.inventory.api.InventoryApi;
import vn.essvn.erpcafe.inventory.api.StockLine;
import vn.essvn.erpcafe.support.AbstractIntegrationTest;

/**
 * End-to-end inventory: purchase-order receiving with moving-average cost,
 * COGS deduction (idempotent, warn-but-allow negative), and low-stock alerts.
 */
class InventoryFlowIntegrationTest extends AbstractIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired
    private InventoryApi inventoryApi;

    private String token;
    private String branchId;

    private void setUp() throws Exception {
        token = adminToken();
        branchId = ((List<String>) JsonPath.read(
                mockMvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, authHeader(token)))
                        .andReturn().getResponse().getContentAsString(),
                "$.branchIds")).get(0);
    }

    private MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder b) {
        return b.header(HttpHeaders.AUTHORIZATION, authHeader(token)).header("X-Branch-Id", branchId);
    }

    private String create(String url, String body) throws Exception {
        return mockMvc.perform(authed(post(url)).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is2xxSuccessful())
                .andReturn().getResponse().getContentAsString();
    }

    /** Creates an ingredient + supplier, then a received PO of {@code qty} at {@code unitCost}. Returns the ingredient id. */
    private String receivePurchase(int n, BigDecimal qty, String unitCost) throws Exception {
        String ingredientId = JsonPath.read(create("/api/v1/ingredients",
                "{\"name\":\"Coffee%d\",\"baseUnit\":\"g\"}".formatted(n)), "$.id");
        String supplierId = JsonPath.read(create("/api/v1/suppliers",
                "{\"name\":\"Roaster%d\"}".formatted(n)), "$.id");
        String poJson = create("/api/v1/purchase-orders",
                "{\"supplierId\":\"%s\",\"lines\":[{\"ingredientId\":\"%s\",\"orderedQty\":%s,\"unitCost\":%s}]}"
                        .formatted(supplierId, ingredientId, qty.toPlainString(), unitCost));
        String poId = JsonPath.read(poJson, "$.id");
        String lineId = JsonPath.read(poJson, "$.lines[0].id");
        mockMvc.perform(authed(post("/api/v1/purchase-orders/" + poId + "/send"))).andExpect(status().isOk());
        mockMvc.perform(authed(post("/api/v1/purchase-orders/" + poId + "/receive"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"receipts\":[{\"lineId\":\"%s\",\"receivedQty\":%s}]}".formatted(lineId, qty.toPlainString())))
                .andExpect(status().isOk())
                .andExpect(jsonPathStatus());
        return ingredientId;
    }

    private static org.springframework.test.web.servlet.ResultMatcher jsonPathStatus() {
        return org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.status").value("RECEIVED");
    }

    private BigDecimal stockField(String ingredientId, String field) throws Exception {
        String json = mockMvc.perform(get("/api/v1/stock").header(HttpHeaders.AUTHORIZATION, authHeader(token))
                        .header("X-Branch-Id", branchId))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        List<Object> vals = JsonPath.read(json, "$[?(@.ingredientId=='%s')].%s".formatted(ingredientId, field));
        return vals.isEmpty() ? null : new BigDecimal(String.valueOf(vals.get(0)));
    }

    @Test
    void purchaseReceipts_blendMovingAverageCost() throws Exception {
        setUp();
        int n = SEQ.incrementAndGet();
        String ingredientId = receivePurchase(n, new BigDecimal("1000"), "0.20");

        assertThat(stockField(ingredientId, "quantityOnHand")).isEqualByComparingTo("1000");
        assertThat(stockField(ingredientId, "avgUnitCost.amount")).isEqualByComparingTo("0.20");

        // second receipt at a different price → moving average = (1000*0.20 + 1000*0.24)/2000 = 0.22
        String supplierId = JsonPath.read(create("/api/v1/suppliers", "{\"name\":\"Roaster2-%d\"}".formatted(n)), "$.id");
        String poJson = create("/api/v1/purchase-orders",
                "{\"supplierId\":\"%s\",\"lines\":[{\"ingredientId\":\"%s\",\"orderedQty\":1000,\"unitCost\":0.24}]}"
                        .formatted(supplierId, ingredientId));
        String poId = JsonPath.read(poJson, "$.id");
        String lineId = JsonPath.read(poJson, "$.lines[0].id");
        mockMvc.perform(authed(post("/api/v1/purchase-orders/" + poId + "/send"))).andExpect(status().isOk());
        mockMvc.perform(authed(post("/api/v1/purchase-orders/" + poId + "/receive"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"receipts\":[{\"lineId\":\"%s\",\"receivedQty\":1000}]}".formatted(lineId)))
                .andExpect(status().isOk());

        assertThat(stockField(ingredientId, "quantityOnHand")).isEqualByComparingTo("2000");
        assertThat(stockField(ingredientId, "avgUnitCost.amount")).isEqualByComparingTo("0.22");
    }

    @Test
    void deductForOrder_capturesCogs_andIsIdempotent() throws Exception {
        setUp();
        int n = SEQ.incrementAndGet();
        String ingredientId = receivePurchase(n, new BigDecimal("1000"), "0.20");
        UUID orderId = UUID.randomUUID();

        // deduct 600g → COGS = 600 * 0.20 = 120
        CogsResult first = inventoryApi.deductForOrder(UUID.fromString(branchId),
                List.of(new StockLine(UUID.fromString(ingredientId), new BigDecimal("600"), "g")), "ORDER", orderId);
        assertThat(first.totalCost().getAmount()).isEqualByComparingTo("120");
        assertThat(stockField(ingredientId, "quantityOnHand")).isEqualByComparingTo("400");

        // repeat with same ref → idempotent: same COGS, no further deduction
        CogsResult again = inventoryApi.deductForOrder(UUID.fromString(branchId),
                List.of(new StockLine(UUID.fromString(ingredientId), new BigDecimal("600"), "g")), "ORDER", orderId);
        assertThat(again.totalCost().getAmount()).isEqualByComparingTo("120");
        assertThat(stockField(ingredientId, "quantityOnHand")).isEqualByComparingTo("400");
    }

    @Test
    void deductForOrder_allowsNegativeStock() throws Exception {
        setUp();
        int n = SEQ.incrementAndGet();
        String ingredientId = receivePurchase(n, new BigDecimal("100"), "0.30");

        // deduct 150g from 100g on hand → allowed, stock goes to -50
        inventoryApi.deductForOrder(UUID.fromString(branchId),
                List.of(new StockLine(UUID.fromString(ingredientId), new BigDecimal("150"), "g")), "ORDER", UUID.randomUUID());
        assertThat(stockField(ingredientId, "quantityOnHand")).isEqualByComparingTo("-50");
    }

    @Test
    void lowStock_reflectsReorderLevel() throws Exception {
        setUp();
        int n = SEQ.incrementAndGet();
        String ingredientId = receivePurchase(n, new BigDecimal("1000"), "0.20");

        // reorder level 500, stock 1000 → not low
        mockMvc.perform(authed(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/v1/stock/reorder-level"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ingredientId\":\"%s\",\"reorderLevel\":500}".formatted(ingredientId)))
                .andExpect(status().isOk());
        assertThat(isLow(ingredientId)).isFalse();

        // waste 700 → stock 300 < 500 → now low
        mockMvc.perform(authed(post("/api/v1/stock/waste"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ingredientId\":\"%s\",\"quantity\":700,\"note\":\"spill\"}".formatted(ingredientId)))
                .andExpect(status().isOk());
        assertThat(isLow(ingredientId)).isTrue();
    }

    private boolean isLow(String ingredientId) throws Exception {
        String json = mockMvc.perform(get("/api/v1/stock/low").header(HttpHeaders.AUTHORIZATION, authHeader(token))
                        .header("X-Branch-Id", branchId))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        List<Object> ids = JsonPath.read(json, "$[?(@.ingredientId=='%s')].ingredientId".formatted(ingredientId));
        return !ids.isEmpty();
    }
}
