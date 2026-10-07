package vn.essvn.erpcafe.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.jayway.jsonpath.JsonPath;

import vn.essvn.erpcafe.common.context.TenantContext;
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
    private UUID companyId;

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
                "{\"supplierId\":\"%s\",\"lines\":[{\"itemId\":\"%s\",\"orderedQty\":1000,\"unitCost\":%s}]}"
                        .formatted(supplierId, itemFor(token, branchId, supplierId, ingredientId), cost));
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
        String me = mockMvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, authHeader(token)))
                .andReturn().getResponse().getContentAsString();
        branchId = ((List<String>) JsonPath.read(me, "$.branchIds")).get(0);
        companyId = UUID.fromString(JsonPath.read(me, "$.companyId"));

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
        assertThat(netDepletionCost(orderId, endpoint.equals("/void") ? "ORDER_VOID" : "ORDER_REFUND"))
                .isEqualByComparingTo("0");
        // 18 g come back at the 0.20 they were taken at: (1982 @ avg + 18 @ 0.20) / 2000
        BigDecimal expectedAvg = beforeVoid.multiply(avgBeforeVoid).add(new BigDecimal("3.6"))
                .divide(new BigDecimal("2000"), 4, java.math.RoundingMode.HALF_EVEN);
        assertThat(avgCost(ingredientId)).isEqualByComparingTo(expectedAvg);

        String reversalRef = endpoint.equals("/void") ? "ORDER_VOID" : "ORDER_REFUND";
        long before = TenantContext.runAsSystem(
                () -> movements.findByRefTypeAndRefId(reversalRef, UUID.fromString(orderId))).size();
        assertThat(before).isEqualTo(1);
        CogsResult again = TenantContext.runAs(companyId, () -> inventoryApi.reverseDeduction(
                UUID.fromString(branchId), "ORDER", UUID.fromString(orderId), reversalRef));
        assertThat(again.totalCost().getAmount()).isEqualByComparingTo("0");
        List<StockMovement> reversals = TenantContext.runAsSystem(
                () -> movements.findByRefTypeAndRefId(reversalRef, UUID.fromString(orderId)));
        assertThat(reversals).hasSize(1);
        assertThat(reversals.get(0).getType()).isEqualTo(MovementType.SALE_REVERSAL);
        assertThat(reversals.get(0).getQuantity()).isEqualByComparingTo("18");
        assertThat(reversals.get(0).getUnitCost().getAmount()).isEqualByComparingTo("0.20");
        assertThat(stockQty(ingredientId)).isEqualByComparingTo(beforeVoid.add(new BigDecimal("18")));
    }

    /** Σ depletion cost − Σ reversal cost for one order's own references (the branch is shared by every test). */
    private BigDecimal netDepletionCost(String orderId, String reversalRef) {
        UUID id = UUID.fromString(orderId);
        BigDecimal net = BigDecimal.ZERO;
        // Depletions are negative quantities, reversals positive: a signed cost nets them directly.
        List<StockMovement> depletions = TenantContext.runAsSystem(() -> movements.findByRefTypeAndRefId("ORDER", id));
        List<StockMovement> reversals =
                TenantContext.runAsSystem(() -> movements.findByRefTypeAndRefId(reversalRef, id));
        assertThat(depletions).isNotEmpty();
        assertThat(reversals).isNotEmpty();
        for (StockMovement m : depletions) {
            net = net.subtract(m.getQuantity().multiply(m.getUnitCost().getAmount()));
        }
        for (StockMovement m : reversals) {
            net = net.subtract(m.getQuantity().multiply(m.getUnitCost().getAmount()));
        }
        return net;
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

    @Test
    void void_ofANegativeNetConsumption_netsStockAndCostToZero() throws Exception {
        String s = UUID.randomUUID().toString().substring(0, 8);
        token = adminToken();
        branchId = ((List<String>) JsonPath.read(
                mockMvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, authHeader(token)))
                        .andReturn().getResponse().getContentAsString(), "$.branchIds")).get(0);
        String ingredientId = JsonPath.read(create("/api/v1/ingredients",
                "{\"name\":\"Sugar-%s\",\"baseUnit\":\"g\"}".formatted(s)), "$.id");
        String supplierId = JsonPath.read(create("/api/v1/suppliers", "{\"name\":\"Sugar Co-%s\"}".formatted(s)), "$.id");
        receive(supplierId, ingredientId, "0.20");
        String categoryId = JsonPath.read(create("/api/v1/categories",
                "{\"name\":\"Drinks-%s\",\"displayOrder\":1}".formatted(s)), "$.id");
        String productId = JsonPath.read(create("/api/v1/products",
                "{\"categoryId\":\"%s\",\"name\":\"Tea-%s\",\"sku\":\"TEA-%s\",\"basePrice\":25000}"
                        .formatted(categoryId, s, s)), "$.id");
        setRecipe(productId, ingredientId, 8);
        // "No sugar" removes more than the recipe uses, so the net consumption is -2 g.
        String groupId = JsonPath.read(create("/api/v1/modifier-groups",
                "{\"name\":\"Sugar-%s\",\"minSelect\":0,\"maxSelect\":1}".formatted(s)), "$.id");
        String groupJson = create("/api/v1/modifier-groups/" + groupId + "/modifiers",
                "{\"name\":\"No sugar\",\"priceDelta\":0,\"displayOrder\":1,\"recipeDeltas\":"
                        + "[{\"ingredientId\":\"%s\",\"quantity\":-10,\"unit\":\"g\"}]}".formatted(ingredientId));
        String modifierId = JsonPath.read(groupJson, "$.id");
        mockMvc.perform(authed(put("/api/v1/products/" + productId + "/modifier-groups"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"modifierGroupIds\":[\"%s\"]}".formatted(groupId)))
                .andExpect(status().isOk());

        BigDecimal start = stockQty(ingredientId);
        String orderId = JsonPath.read(create("/api/v1/orders",
                "{\"orderType\":\"DINE_IN\",\"lines\":[{\"productId\":\"%s\",\"quantity\":1,\"modifierIds\":[\"%s\"]}]}"
                        .formatted(productId, modifierId)), "$.id");
        mockMvc.perform(authed(post("/api/v1/orders/" + orderId + "/payments"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"method\":\"CASH\",\"amount\":25000}"))
                .andExpect(status().isOk());
        assertThat(stockQty(ingredientId)).isEqualByComparingTo(start.add(new BigDecimal("2")));

        mockMvc.perform(authed(post("/api/v1/orders/" + orderId + "/void"))).andExpect(status().isOk());

        assertThat(stockQty(ingredientId)).isEqualByComparingTo(start);
        assertThat(netDepletionCost(orderId, "ORDER_VOID")).isEqualByComparingTo("0");
    }
}
