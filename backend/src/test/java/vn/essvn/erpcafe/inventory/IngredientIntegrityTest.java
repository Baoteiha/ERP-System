package vn.essvn.erpcafe.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.jayway.jsonpath.JsonPath;

import vn.essvn.erpcafe.common.context.TenantContext;
import vn.essvn.erpcafe.common.exception.BusinessRuleException;
import vn.essvn.erpcafe.inventory.api.StockLine;
import vn.essvn.erpcafe.inventory.application.StockLedgerService;
import vn.essvn.erpcafe.inventory.domain.Ingredient;
import vn.essvn.erpcafe.inventory.persistence.IngredientRepository;
import vn.essvn.erpcafe.support.AbstractIntegrationTest;

/**
 * Class: IngredientIntegrityTest
 * Purpose: Proves ingredient units, deletes and the moving-average cost cannot be left in a state that corrupts stock.
 */
class IngredientIntegrityTest extends AbstractIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired
    private StockLedgerService ledger;

    @Autowired
    private IngredientRepository ingredients;

    private String token;
    private String branchId;
    private UUID companyId;

    @BeforeEach
    void signIn() throws Exception {
        token = adminToken();
        String me = mockMvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, authHeader(token)))
                .andReturn().getResponse().getContentAsString();
        branchId = ((List<String>) JsonPath.read(me, "$.branchIds")).get(0);
        companyId = UUID.fromString(JsonPath.read(me, "$.companyId"));
    }

    /** Deducts at the admin's branch as its company, as a sale completed there would. */
    private void deduct(String ingredientId, String quantity, String unit, String refType) {
        TenantContext.runAs(companyId, () -> ledger.deduct(UUID.fromString(branchId),
                List.of(new StockLine(UUID.fromString(ingredientId), new BigDecimal(quantity), unit)),
                refType, UUID.randomUUID()));
    }

    private MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder b) {
        return b.header(HttpHeaders.AUTHORIZATION, authHeader(token)).header("X-Branch-Id", branchId);
    }

    private String create(String url, String body) throws Exception {
        return mockMvc.perform(authed(post(url)).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is2xxSuccessful())
                .andReturn().getResponse().getContentAsString();
    }

    private String ingredient(String baseUnit) throws Exception {
        return JsonPath.read(create("/api/v1/ingredients",
                "{\"name\":\"Integrity%d\",\"baseUnit\":\"%s\"}".formatted(SEQ.incrementAndGet(), baseUnit)), "$.id");
    }

    private String supplier() throws Exception {
        return JsonPath.read(create("/api/v1/suppliers",
                "{\"name\":\"IntegritySupplier%d\"}".formatted(SEQ.incrementAndGet())), "$.id");
    }

    /** Creates a PO for the ingredient and sends it; returns {poId, lineId}. */
    private String[] sentPurchaseOrder(String ingredientId, String qty, String cost) throws Exception {
        String poJson = create("/api/v1/purchase-orders",
                "{\"supplierId\":\"%s\",\"lines\":[{\"ingredientId\":\"%s\",\"orderedQty\":%s,\"unitCost\":%s}]}"
                        .formatted(supplier(), ingredientId, qty, cost));
        String poId = JsonPath.read(poJson, "$.id");
        mockMvc.perform(authed(post("/api/v1/purchase-orders/" + poId + "/send"))).andExpect(status().isOk());
        return new String[] {poId, JsonPath.read(poJson, "$.lines[0].id")};
    }

    private void receiveAll(String[] po, String qty) throws Exception {
        mockMvc.perform(authed(post("/api/v1/purchase-orders/" + po[0] + "/receive"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"receipts\":[{\"lineId\":\"%s\",\"receivedQty\":%s}]}".formatted(po[1], qty)))
                .andExpect(status().isOk());
    }

    private void adjust(String ingredientId, int delta) throws Exception {
        create("/api/v1/stock/adjust", "{\"ingredientId\":\"%s\",\"quantityDelta\":%d}".formatted(ingredientId, delta));
    }

    private BigDecimal avgCost(String ingredientId) throws Exception {
        String json = mockMvc.perform(authed(get("/api/v1/stock"))).andReturn().getResponse().getContentAsString();
        List<Object> vals = JsonPath.read(json, "$[?(@.ingredientId=='%s')].avgUnitCost.amount".formatted(ingredientId));
        return new BigDecimal(String.valueOf(vals.get(0)));
    }

    private MockHttpServletRequestBuilder changeUnit(String id, String name, String unit) {
        return authed(put("/api/v1/ingredients/" + id)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"%s\",\"baseUnit\":\"%s\",\"active\":true}".formatted(name, unit));
    }

    /** Reads the ingredient back as the admin's company. */
    private Optional<Ingredient> stored(String id) {
        return TenantContext.runAs(companyId, () -> ingredients.findById(UUID.fromString(id)));
    }

    private String nameOf(String id) {
        return stored(id).orElseThrow().getName();
    }

    @Test
    void receipt_afterOversellTakesTheReceiptCostNotABlendWithTheZeroCost() throws Exception {
        String id = ingredient("g");
        // 100 g owed that were never received: on hand -100 at cost 0. A sale can no longer oversell,
        // but a count correction can still leave a deficit, as can sales made before the stock guard.
        adjust(id, -100);

        receiveAll(sentPurchaseOrder(id, "1000", "0.24"), "1000");

        assertThat(avgCost(id)).isEqualByComparingTo("0.24");
    }

    @Test
    void baseUnit_withStockOnHandCannotChange() throws Exception {
        String id = ingredient("ml");
        adjust(id, 10);

        mockMvc.perform(changeUnit(id, nameOf(id), "l"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));

        assertThat(stored(id).orElseThrow().getBaseUnit()).isEqualTo("ml");
    }

    @Test
    void baseUnit_withAnyMovementCannotChangeEvenAtZeroStock() throws Exception {
        String id = ingredient("g");
        adjust(id, 10);
        adjust(id, -10);

        mockMvc.perform(changeUnit(id, nameOf(id), "kg"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
    }

    @Test
    void baseUnit_toAnotherDimensionIsRefusedEvenWithoutMovements() throws Exception {
        String id = ingredient("g");

        mockMvc.perform(changeUnit(id, nameOf(id), "ml"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("BUSINESS_RULE"));

        assertThat(stored(id).orElseThrow().getBaseUnit()).isEqualTo("g");
    }

    @Test
    void baseUnit_withinTheSameDimensionChangesWhenNothingWasEverStocked() throws Exception {
        String id = ingredient("g");

        mockMvc.perform(changeUnit(id, nameOf(id), "kg"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.baseUnit").value("kg"));
    }

    @Test
    void delete_withStockAtABranchIsRefused() throws Exception {
        String id = ingredient("g");
        adjust(id, 5);

        mockMvc.perform(authed(delete("/api/v1/ingredients/" + id)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));

        assertThat(stored(id)).isPresent();
    }

    @Test
    void delete_withAnOpenPurchaseOrderLineIsRefused() throws Exception {
        String id = ingredient("g");
        sentPurchaseOrder(id, "500", "0.20");

        mockMvc.perform(authed(delete("/api/v1/ingredients/" + id)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));

        assertThat(stored(id)).isPresent();
    }

    @Test
    void delete_usedByAProductRecipeIsRefused() throws Exception {
        String id = ingredient("g");
        int n = SEQ.incrementAndGet();
        String categoryId = JsonPath.read(create("/api/v1/categories",
                "{\"name\":\"IntCat%d\",\"displayOrder\":1}".formatted(n)), "$.id");
        String productId = JsonPath.read(create("/api/v1/products",
                "{\"categoryId\":\"%s\",\"name\":\"IntProd%d\",\"sku\":\"INT%d\",\"basePrice\":1000}"
                        .formatted(categoryId, n, n)), "$.id");
        mockMvc.perform(authed(put("/api/v1/products/" + productId + "/recipe")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lines\":[{\"ingredientId\":\"%s\",\"quantity\":5,\"unit\":\"g\"}]}".formatted(id)))
                .andExpect(status().isOk());

        mockMvc.perform(authed(delete("/api/v1/ingredients/" + id)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));

        assertThat(stored(id)).isPresent();
    }

    @Test
    void delete_usedByAModifierRecipeDeltaIsRefused() throws Exception {
        String id = ingredient("g");
        String groupId = JsonPath.read(create("/api/v1/modifier-groups",
                "{\"name\":\"IntGrp%d\",\"minSelect\":0,\"maxSelect\":1}".formatted(SEQ.incrementAndGet())), "$.id");
        create("/api/v1/modifier-groups/" + groupId + "/modifiers",
                "{\"name\":\"Extra\",\"priceDelta\":0,\"displayOrder\":1,\"recipeDeltas\":[{\"ingredientId\":\"%s\",\"quantity\":7,\"unit\":\"g\"}]}"
                        .formatted(id));

        mockMvc.perform(authed(delete("/api/v1/ingredients/" + id)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));

        assertThat(stored(id)).isPresent();
    }

    @Test
    void delete_withNoStockAndNoOpenOrderSucceeds() throws Exception {
        String id = ingredient("g");

        mockMvc.perform(authed(delete("/api/v1/ingredients/" + id))).andExpect(status().isNoContent());

        assertThat(stored(id)).isEmpty();
    }

    @Test
    void deduct_againstASoftDeletedIngredientNamesItInTheRefusal() throws Exception {
        String id = ingredient("g");
        mockMvc.perform(authed(delete("/api/v1/ingredients/" + id))).andExpect(status().isNoContent());

        assertThatThrownBy(() -> deduct(id, "1", "kg", "TEST_DEDUCT"))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining(id);
    }
}
