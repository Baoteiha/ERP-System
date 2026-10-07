package vn.essvn.erpcafe.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
 * Class: PurchaseLineUnitTest
 * Purpose: A purchase line is a supplier item, counted and priced in the item's unit.
 */
class PurchaseLineUnitTest extends AbstractIntegrationTest {

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

    private String create(String url, String body) throws Exception {
        return mockMvc.perform(authed(post(url)).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is2xxSuccessful())
                .andReturn().getResponse().getContentAsString();
    }

    private String ingredient(String baseUnit) throws Exception {
        return JsonPath.read(create("/api/v1/ingredients",
                "{\"name\":\"LineUnitBeans%d\",\"baseUnit\":\"%s\"}".formatted(SEQ.incrementAndGet(), baseUnit)),
                "$.id");
    }

    private String supplier() throws Exception {
        return JsonPath.read(create("/api/v1/suppliers",
                "{\"name\":\"LineUnitRoaster%d\"}".formatted(SEQ.incrementAndGet())), "$.id");
    }

    private String item(String supplierId, String ingredientId, String unit) throws Exception {
        return JsonPath.read(create("/api/v1/items",
                "{\"name\":\"Arabica\",\"supplierId\":\"%s\",\"ingredientId\":\"%s\",\"unit\":\"%s\"}"
                        .formatted(supplierId, ingredientId, unit)), "$.id");
    }

    private String order(String supplierId, String line) throws Exception {
        String json = create("/api/v1/purchase-orders",
                "{\"supplierId\":\"%s\",\"lines\":[%s]}".formatted(supplierId, line));
        String poId = JsonPath.read(json, "$.id");
        mockMvc.perform(authed(post("/api/v1/purchase-orders/" + poId + "/send"))).andExpect(status().isOk());
        return json;
    }

    private void receive(String poJson, String receipt) throws Exception {
        String poId = JsonPath.read(poJson, "$.id");
        String lineId = JsonPath.read(poJson, "$.lines[0].id");
        mockMvc.perform(authed(post("/api/v1/purchase-orders/" + poId + "/receive"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"receipts\":[{\"lineId\":\"%s\",%s}]}".formatted(lineId, receipt)))
                .andExpect(status().isOk());
    }

    private BigDecimal stockField(String ingredientId, String field) throws Exception {
        String json = mockMvc.perform(authed(get("/api/v1/stock")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        List<Object> vals = JsonPath.read(json, "$[?(@.ingredientId=='%s')].%s".formatted(ingredientId, field));
        return vals.isEmpty() ? null : new BigDecimal(String.valueOf(vals.get(0)));
    }

    @Test
    void lineFromAnItem_takesItsUnit_andReceivesAtTheRatePerBaseUnit() throws Exception {
        String ingredientId = ingredient("g");
        String supplierId = supplier();
        String itemId = item(supplierId, ingredientId, "kg");

        // 10 kg at 210,000 per kg; no receipt unit, so the delivery is counted in the line's kg.
        String po = order(supplierId, "{\"itemId\":\"%s\",\"orderedQty\":10,\"unitCost\":210000}".formatted(itemId));
        assertThat((String) JsonPath.read(po, "$.lines[0].unit")).isEqualTo("kg");
        assertThat((String) JsonPath.read(po, "$.lines[0].itemId")).isEqualTo(itemId);
        assertThat((String) JsonPath.read(po, "$.lines[0].ingredientId")).isEqualTo(ingredientId);

        receive(po, "\"receivedQty\":10");

        assertThat(stockField(ingredientId, "quantityOnHand")).isEqualByComparingTo("10000");
        assertThat(stockField(ingredientId, "avgUnitCost.amount")).isEqualByComparingTo("210");
    }

    @Test
    void ingredientLine_isRefusedWithAClearMessage() throws Exception {
        String ingredientId = ingredient("g");
        mockMvc.perform(authed(post("/api/v1/purchase-orders")).contentType(MediaType.APPLICATION_JSON)
                        .content(("{\"supplierId\":\"%s\",\"lines\":[{\"ingredientId\":\"%s\",\"unit\":\"kg\","
                                + "\"orderedQty\":10,\"unitCost\":210000}]}").formatted(supplier(), ingredientId)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("must be a supplier item")));
    }

    @Test
    void packItemLine_copiesThePack_andRefusesHalfAPack() throws Exception {
        String ingredientId = ingredient("pc");
        String supplierId = supplier();
        String boxId = JsonPath.read(create("/api/v1/items",
                ("{\"name\":\"Straws box\",\"supplierId\":\"%s\",\"ingredientId\":\"%s\",\"unit\":\"pc\","
                        + "\"packLabel\":\"hộp\",\"packSize\":100}").formatted(supplierId, ingredientId)), "$.id");

        String po = order(supplierId, "{\"itemId\":\"%s\",\"orderedQty\":5,\"unitCost\":45000}".formatted(boxId));
        assertThat((String) JsonPath.read(po, "$.lines[0].packLabel")).isEqualTo("hộp");
        assertThat(new BigDecimal(String.valueOf((Object) JsonPath.read(po, "$.lines[0].packSize"))))
                .isEqualByComparingTo("100");
        assertThat((String) JsonPath.read(po, "$.kind")).isEqualTo("ORDER");

        mockMvc.perform(authed(post("/api/v1/purchase-orders")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"supplierId\":\"%s\",\"lines\":[{\"itemId\":\"%s\",\"orderedQty\":2.5,\"unitCost\":1}]}"
                                .formatted(supplierId, boxId)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("Packs are counted whole")));
    }

    @Test
    void receiptInAnotherUnit_countsAgainstTheLinesOutstandingQuantity() throws Exception {
        String ingredientId = ingredient("g");
        String supplierId = supplier();
        String po = order(supplierId, "{\"itemId\":\"%s\",\"orderedQty\":10,\"unitCost\":210000}"
                .formatted(item(supplierId, ingredientId, "kg")));

        receive(po, "\"receivedQty\":4000,\"unit\":\"g\"");
        String poId = JsonPath.read(po, "$.id");
        mockMvc.perform(authed(get("/api/v1/purchase-orders/" + poId)))
                .andExpect(jsonPath("$.lines[0].receivedQty").value(4.0))
                .andExpect(jsonPath("$.status").value("PARTIALLY_RECEIVED"));

        // 7 kg more would make 11 against the 10 ordered.
        String lineId = JsonPath.read(po, "$.lines[0].id");
        mockMvc.perform(authed(post("/api/v1/purchase-orders/" + poId + "/receive"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"receipts\":[{\"lineId\":\"%s\",\"receivedQty\":7}]}".formatted(lineId)))
                .andExpect(status().isUnprocessableEntity());
        assertThat(stockField(ingredientId, "quantityOnHand")).isEqualByComparingTo("4000");
    }

    @Test
    void costOverride_isPerTheReceiptUnit() throws Exception {
        String ingredientId = ingredient("g");
        String supplierId = supplier();
        String po = order(supplierId, "{\"itemId\":\"%s\",\"orderedQty\":10,\"unitCost\":210000}"
                .formatted(item(supplierId, ingredientId, "kg")));

        receive(po, "\"receivedQty\":10,\"unitCostOverride\":200000");

        assertThat(stockField(ingredientId, "avgUnitCost.amount")).isEqualByComparingTo("200");
    }

    @Test
    void lineRejectsAUnitOfAnotherDimension_andAmbiguousSources() throws Exception {
        String ingredientId = ingredient("g");
        String supplierId = supplier();
        String itemId = item(supplierId, ingredientId, "kg");

        for (String line : List.of(
                "{\"ingredientId\":\"%s\",\"unit\":\"l\",\"orderedQty\":1,\"unitCost\":1}".formatted(ingredientId),
                "{\"orderedQty\":1,\"unitCost\":1}",
                "{\"itemId\":\"%s\",\"ingredientId\":\"%s\",\"orderedQty\":1,\"unitCost\":1}"
                        .formatted(itemId, ingredientId),
                "{\"itemId\":\"%s\",\"unit\":\"g\",\"orderedQty\":1,\"unitCost\":1}".formatted(itemId))) {
            mockMvc.perform(authed(post("/api/v1/purchase-orders")).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"supplierId\":\"%s\",\"lines\":[%s]}".formatted(supplierId, line)))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.code").exists());
        }
    }

    @Test
    void itemLine_mustBelongToTheOrdersSupplier() throws Exception {
        String ingredientId = ingredient("g");
        String supplierS = supplier();
        String supplierT = supplier();
        String itemOfS = item(supplierS, ingredientId, "kg");
        String itemOfT = item(supplierT, ingredientId, "kg");
        int before = ((List<?>) JsonPath.read(mockMvc.perform(authed(get("/api/v1/purchase-orders")))
                .andReturn().getResponse().getContentAsString(), "$.content")).size();

        mockMvc.perform(authed(post("/api/v1/purchase-orders")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"supplierId\":\"%s\",\"lines\":[{\"itemId\":\"%s\",\"orderedQty\":1,\"unitCost\":1}]}"
                                .formatted(supplierS, itemOfT)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("BUSINESS_RULE"));

        int after = ((List<?>) JsonPath.read(mockMvc.perform(authed(get("/api/v1/purchase-orders")))
                .andReturn().getResponse().getContentAsString(), "$.content")).size();
        assertThat(after).isEqualTo(before);

        mockMvc.perform(authed(post("/api/v1/purchase-orders")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"supplierId\":\"%s\",\"lines\":[{\"itemId\":\"%s\",\"orderedQty\":1,\"unitCost\":1}]}"
                                .formatted(supplierS, itemOfS)))
                .andExpect(status().isCreated());
    }
}
