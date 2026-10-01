package vn.essvn.erpcafe.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.jayway.jsonpath.JsonPath;

import vn.essvn.erpcafe.inventory.api.CogsResult;
import vn.essvn.erpcafe.inventory.api.InventoryApi;
import vn.essvn.erpcafe.inventory.domain.MovementType;
import vn.essvn.erpcafe.inventory.domain.StockMovement;
import vn.essvn.erpcafe.inventory.persistence.StockMovementRepository;
import vn.essvn.erpcafe.support.AbstractIntegrationTest;

/**
 * Class: ReverseDeductionIntegrationTest
 * Purpose: Proves void and refund return exactly what the sale took, even after the recipe and cost changed.
 */
class ReverseDeductionIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private InventoryApi inventoryApi;

    @Autowired
    private StockMovementRepository movements;

    private String token;
    private String branchId;

    private MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder b) {
        return b.header(HttpHeaders.AUTHORIZATION, authHeader(token)).header("X-Branch-Id", branchId);
    }

    private String create(String url, String body) throws Exception {
        return mockMvc.perform(authed(post(url)).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is2xxSuccessful())
                .andReturn().getResponse().getContentAsString();
    }

    private void receive(String supplierId, String ingredientId, String cost) throws Exception {
        String poJson = create("/api/v1/purchase-orders",
                "{\"supplierId\":\"%s\",\"lines\":[{\"ingredientId\":\"%s\",\"orderedQty\":1000,\"unitCost\":%s}]}"
                        .formatted(supplierId, ingredientId, cost));
        String poId = JsonPath.read(poJson, "$.id");
        String lineId = JsonPath.read(poJson, "$.lines[0].id");
        mockMvc.perform(authed(post("/api/v1/purchase-orders/" + poId + "/send"))).andExpect(status().isOk());
        mockMvc.perform(authed(post("/api/v1/purchase-orders/" + poId + "/receive"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"receipts\":[{\"lineId\":\"%s\",\"receivedQty\":1000}]}".formatted(lineId)))
                .andExpect(status().isOk());
    }

    private BigDecimal stockQty(String ingredientId) throws Exception {
        String json = mockMvc.perform(get("/api/v1/stock").header(HttpHeaders.AUTHORIZATION, authHeader(token))
                        .header("X-Branch-Id", branchId))
                .andReturn().getResponse().getContentAsString();
        List<Object> vals = JsonPath.read(json, "$[?(@.ingredientId=='%s')].quantityOnHand".formatted(ingredientId));
        return new BigDecimal(String.valueOf(vals.get(0)));
    }

    private BigDecimal avgCost(String ingredientId) throws Exception {
        String json = mockMvc.perform(get("/api/v1/stock").header(HttpHeaders.AUTHORIZATION, authHeader(token))
                        .header("X-Branch-Id", branchId))
                .andReturn().getResponse().getContentAsString();
        List<Object> vals = JsonPath.read(json, "$[?(@.ingredientId=='%s')].avgUnitCost.amount".formatted(ingredientId));
        return new BigDecimal(String.valueOf(vals.get(0)));
    }

    /** Sells one 18 g latte, then changes the recipe to 20 g and receives more stock at 0.30. */
    private void soldThenRecipeAndCostChanged(String endpoint) throws Exception {
        String s = UUID.randomUUID().toString().substring(0, 8);
        token = adminToken();
        branchId = ((List<String>) JsonPath.read(
                mockMvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, authHeader(token)))
                        .andReturn().getResponse().getContentAsString(), "$.branchIds")).get(0);

        String ingredientId = JsonPath.read(create("/api/v1/ingredients",
                "{\"name\":\"Coffee-%s\",\"baseUnit\":\"g\"}".formatted(s)), "$.id");
        String supplierId = JsonPath.read(create("/api/v1/suppliers", "{\"name\":\"Roaster-%s\"}".formatted(s)), "$.id");
        receive(supplierId, ingredientId, "0.20");
        String categoryId = JsonPath.read(create("/api/v1/categories",
                "{\"name\":\"Drinks-%s\",\"displayOrder\":1}".formatted(s)), "$.id");
        String productId = JsonPath.read(create("/api/v1/products",
                "{\"categoryId\":\"%s\",\"name\":\"Latte-%s\",\"sku\":\"LAT-%s\",\"basePrice\":25000}"
                        .formatted(categoryId, s, s)), "$.id");
        setRecipe(productId, ingredientId, 18);

        Instant from = Instant.now().minusSeconds(1);
        String orderId = JsonPath.read(create("/api/v1/orders",
                "{\"orderType\":\"DINE_IN\",\"lines\":[{\"productId\":\"%s\",\"quantity\":1}]}".formatted(productId)),
                "$.id");
        mockMvc.perform(authed(post("/api/v1/orders/" + orderId + "/payments"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"method\":\"CASH\",\"amount\":25000}"))
                .andExpect(status().isOk());
        assertThat(stockQty(ingredientId)).isEqualByComparingTo("982");

        setRecipe(productId, ingredientId, 20);
        receive(supplierId, ingredientId, "0.30");
        BigDecimal beforeVoid = stockQty(ingredientId);
        BigDecimal avgBeforeVoid = avgCost(ingredientId);
        assertThat(beforeVoid).isEqualByComparingTo("1982");

        mockMvc.perform(authed(post("/api/v1/orders/" + orderId + endpoint))).andExpect(status().isOk());

        assertThat(stockQty(ingredientId)).isEqualByComparingTo(beforeVoid.add(new BigDecimal("18")));
        assertThat(inventoryApi.depletionCost(UUID.fromString(branchId), from, Instant.now().plusSeconds(60)))
                .isEqualByComparingTo("0");
        // 18 g come back at the 0.20 they were taken at: (1982 @ avg + 18 @ 0.20) / 2000
        BigDecimal expectedAvg = beforeVoid.multiply(avgBeforeVoid).add(new BigDecimal("3.6"))
                .divide(new BigDecimal("2000"), 4, java.math.RoundingMode.HALF_EVEN);
        assertThat(avgCost(ingredientId)).isEqualByComparingTo(expectedAvg);

        String reversalRef = endpoint.equals("/void") ? "ORDER_VOID" : "ORDER_REFUND";
        long before = movements.findByRefTypeAndRefId(reversalRef, UUID.fromString(orderId)).size();
        assertThat(before).isEqualTo(1);
        CogsResult again = inventoryApi.reverseDeduction(UUID.fromString(branchId), "ORDER",
                UUID.fromString(orderId), reversalRef);
        assertThat(again.totalCost().getAmount()).isEqualByComparingTo("0");
        List<StockMovement> reversals = movements.findByRefTypeAndRefId(reversalRef, UUID.fromString(orderId));
        assertThat(reversals).hasSize(1);
        assertThat(reversals.get(0).getType()).isEqualTo(MovementType.SALE_REVERSAL);
        assertThat(reversals.get(0).getQuantity()).isEqualByComparingTo("18");
        assertThat(reversals.get(0).getUnitCost().getAmount()).isEqualByComparingTo("0.20");
        assertThat(stockQty(ingredientId)).isEqualByComparingTo(beforeVoid.add(new BigDecimal("18")));
    }

    private void setRecipe(String productId, String ingredientId, int grams) throws Exception {
        mockMvc.perform(authed(put("/api/v1/products/" + productId + "/recipe"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lines\":[{\"ingredientId\":\"%s\",\"quantity\":%d,\"unit\":\"g\"}]}"
                                .formatted(ingredientId, grams)))
                .andExpect(status().isOk());
    }

    @Test
    void void_returnsTheRecordedQuantityAtTheRecordedCost_notTheCurrentRecipe() throws Exception {
        soldThenRecipeAndCostChanged("/void");
    }

    @Test
    void refund_returnsTheRecordedQuantityAtTheRecordedCost_notTheCurrentRecipe() throws Exception {
        soldThenRecipeAndCostChanged("/refund");
    }
}
