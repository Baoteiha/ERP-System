package vn.essvn.erpcafe.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.jayway.jsonpath.JsonPath;

import vn.essvn.erpcafe.support.AbstractIntegrationTest;

/**
 * Class: InventoryGuardsTest
 * Purpose: Inactive suppliers, ingredients and items are refused on new purchase orders and receipts, and a zero adjustment is a bad request.
 */
class InventoryGuardsTest extends AbstractIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger();

    private String token;
    private String branchId;

    @BeforeEach
    void login() throws Exception {
        token = adminToken();
        branchId = ((List<String>) JsonPath.read(
                mockMvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, authHeader(token)))
                        .andReturn().getResponse().getContentAsString(), "$.branchIds")).get(0);
    }

    private MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder b) {
        return b.header(HttpHeaders.AUTHORIZATION, authHeader(token)).header("X-Branch-Id", branchId);
    }

    private String send(MockHttpServletRequestBuilder request, String body) throws Exception {
        return mockMvc.perform(authed(request).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is2xxSuccessful())
                .andReturn().getResponse().getContentAsString();
    }

    private String ingredient() throws Exception {
        return JsonPath.read(send(post("/api/v1/ingredients"),
                "{\"name\":\"GuardBeans%d\",\"baseUnit\":\"g\"}".formatted(SEQ.incrementAndGet())), "$.id");
    }

    private String supplier() throws Exception {
        return JsonPath.read(send(post("/api/v1/suppliers"),
                "{\"name\":\"GuardRoaster%d\"}".formatted(SEQ.incrementAndGet())), "$.id");
    }

    private String item(String supplierId, String ingredientId) throws Exception {
        return JsonPath.read(send(post("/api/v1/items"),
                "{\"name\":\"Arabica\",\"supplierId\":\"%s\",\"ingredientId\":\"%s\",\"unit\":\"kg\"}"
                        .formatted(supplierId, ingredientId)), "$.id");
    }

    private void deactivateSupplier(String id) throws Exception {
        send(put("/api/v1/suppliers/" + id), "{\"name\":\"GuardRoaster%d\",\"active\":false}"
                .formatted(SEQ.incrementAndGet()));
    }

    private void deactivateIngredient(String id) throws Exception {
        send(put("/api/v1/ingredients/" + id), "{\"name\":\"GuardBeans%d\",\"baseUnit\":\"g\",\"active\":false}"
                .formatted(SEQ.incrementAndGet()));
    }

    private void deactivateItem(String id, String supplierId, String ingredientId) throws Exception {
        send(put("/api/v1/items/" + id),
                "{\"name\":\"Arabica\",\"supplierId\":\"%s\",\"ingredientId\":\"%s\",\"unit\":\"kg\",\"active\":false}"
                        .formatted(supplierId, ingredientId));
    }

    private String orderBody(String supplierId, String line) {
        return "{\"supplierId\":\"%s\",\"lines\":[%s]}".formatted(supplierId, line);
    }

    private String itemLine(String itemId) {
        return "{\"itemId\":\"%s\",\"orderedQty\":10,\"unitCost\":1}".formatted(itemId);
    }

    private int orderCount() throws Exception {
        return ((List<?>) JsonPath.read(mockMvc.perform(authed(get("/api/v1/purchase-orders")))
                .andReturn().getResponse().getContentAsString(), "$.content")).size();
    }

    private void expectRefused(String body, String messagePart) throws Exception {
        int before = orderCount();
        mockMvc.perform(authed(post("/api/v1/purchase-orders")).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("BUSINESS_RULE"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString(messagePart)));
        assertThat(orderCount()).isEqualTo(before);
    }

    @Test
    void newOrder_forAnInactiveSupplier_isRefused() throws Exception {
        String ingredientId = ingredient();
        String supplierId = supplier();
        String itemId = item(supplierId, ingredientId);
        deactivateSupplier(supplierId);

        expectRefused(orderBody(supplierId, itemLine(itemId)), "inactive");
    }

    @Test
    void newOrder_lineOfAnInactiveItem_isRefused() throws Exception {
        String ingredientId = ingredient();
        String supplierId = supplier();
        String itemId = item(supplierId, ingredientId);
        deactivateItem(itemId, supplierId, ingredientId);

        expectRefused(orderBody(supplierId, "{\"itemId\":\"%s\",\"orderedQty\":1,\"unitCost\":1}".formatted(itemId)),
                "inactive");
    }

    @Test
    void newOrder_itemLineWithABlankUnit_isRefusedWithAClearMessage() throws Exception {
        String ingredientId = ingredient();
        String supplierId = supplier();
        String itemId = item(supplierId, ingredientId);

        expectRefused(orderBody(supplierId,
                "{\"itemId\":\"%s\",\"unit\":\"\",\"orderedQty\":1,\"unitCost\":1}".formatted(itemId)),
                "takes its unit from the item");
    }

    @Test
    void receipt_onAnOrderWhoseSupplierWentInactive_isRefusedAndStockUnchanged() throws Exception {
        String ingredientId = ingredient();
        String supplierId = supplier();
        String po = send(post("/api/v1/purchase-orders"), orderBody(supplierId, itemLine(item(supplierId, ingredientId))));
        String poId = JsonPath.read(po, "$.id");
        String lineId = JsonPath.read(po, "$.lines[0].id");
        mockMvc.perform(authed(post("/api/v1/purchase-orders/" + poId + "/send"))).andExpect(status().isOk());
        deactivateSupplier(supplierId);

        mockMvc.perform(authed(post("/api/v1/purchase-orders/" + poId + "/receive"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"receipts\":[{\"lineId\":\"%s\",\"receivedQty\":1}]}".formatted(lineId)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("BUSINESS_RULE"));

        assertThat(onHand(ingredientId)).isNull();
    }

    @Test
    void receipt_ofAnIngredientThatWentInactive_isRefusedAndStockUnchanged() throws Exception {
        String ingredientId = ingredient();
        String supplierId = supplier();
        String po = send(post("/api/v1/purchase-orders"), orderBody(supplierId, itemLine(item(supplierId, ingredientId))));
        String poId = JsonPath.read(po, "$.id");
        String lineId = JsonPath.read(po, "$.lines[0].id");
        mockMvc.perform(authed(post("/api/v1/purchase-orders/" + poId + "/send"))).andExpect(status().isOk());
        deactivateIngredient(ingredientId);

        mockMvc.perform(authed(post("/api/v1/purchase-orders/" + poId + "/receive"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"receipts\":[{\"lineId\":\"%s\",\"receivedQty\":1}]}".formatted(lineId)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("BUSINESS_RULE"));

        assertThat(onHand(ingredientId)).isNull();
    }

    @Test
    void zeroAdjustment_isABadRequestAndWritesNoMovement() throws Exception {
        String ingredientId = ingredient();

        mockMvc.perform(authed(post("/api/v1/stock/adjust")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ingredientId\":\"%s\",\"quantityDelta\":0}".formatted(ingredientId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").exists());

        String movements = mockMvc.perform(authed(get("/api/v1/stock/movements?ingredientId=" + ingredientId)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat((List<?>) JsonPath.read(movements, "$.content")).isEmpty();
    }

    @Test
    void adjustment_thatRoundsToZeroInTheBaseUnit_isABadRequestToo() throws Exception {
        String ingredientId = ingredient();

        // 0.00001 g, and 0.01 mg (also 0.00001 g): both are nothing at the ledger's four decimals
        for (String delta : List.of("\"quantityDelta\":0.00001", "\"quantityDelta\":0.01,\"unit\":\"mg\"")) {
            mockMvc.perform(authed(post("/api/v1/stock/adjust")).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"ingredientId\":\"%s\",%s}".formatted(ingredientId, delta)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").exists());
        }
        String movements = mockMvc.perform(authed(get("/api/v1/stock/movements?ingredientId=" + ingredientId)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat((List<?>) JsonPath.read(movements, "$.content")).isEmpty();
    }

    @Test
    void newOrder_itemWhoseIngredientIsInactive_isRefusedAtCreate() throws Exception {
        String ingredientId = ingredient();
        String supplierId = supplier();
        String itemId = item(supplierId, ingredientId);
        deactivateIngredient(ingredientId);

        expectRefused(orderBody(supplierId, "{\"itemId\":\"%s\",\"orderedQty\":1,\"unitCost\":1}".formatted(itemId)),
                "inactive");
    }

    private BigDecimal onHand(String ingredientId) throws Exception {
        String json = mockMvc.perform(authed(get("/api/v1/stock")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        List<Object> vals = JsonPath.read(json, "$[?(@.ingredientId=='%s')].quantityOnHand".formatted(ingredientId));
        return vals.isEmpty() ? null : new BigDecimal(String.valueOf(vals.get(0)));
    }
}
