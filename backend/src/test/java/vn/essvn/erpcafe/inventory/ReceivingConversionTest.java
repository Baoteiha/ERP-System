package vn.essvn.erpcafe.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.jayway.jsonpath.JsonPath;

import vn.essvn.erpcafe.support.AbstractIntegrationTest;

/**
 * Receiving converts through the receipt's unit (plan task A3, ADR-0006): a
 * delivery counted in the supplier's unit lands in the ledger denominated in
 * the ingredient's base unit, and a per-received-unit cost override is
 * re-rated per base unit. A receipt with no unit stays base-unit (legacy).
 */
class ReceivingConversionTest extends AbstractIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger();

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

    private BigDecimal stockField(String ingredientId, String field) throws Exception {
        String json = mockMvc.perform(authed(get("/api/v1/stock")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        List<Object> vals = JsonPath.read(json, "$[?(@.ingredientId=='%s')].%s".formatted(ingredientId, field));
        return vals.isEmpty() ? null : new BigDecimal(String.valueOf(vals.get(0)));
    }

    @Test
    void receive_convertsReceiptUnitToTheIngredientsBaseUnit() throws Exception {
        token = adminToken();
        branchId = ((List<String>) JsonPath.read(
                mockMvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, authHeader(token)))
                        .andReturn().getResponse().getContentAsString(), "$.branchIds")).get(0);
        int n = SEQ.incrementAndGet();

        // Ingredient stocked in grams; 2000 g ordered at 0.20/g.
        String ingredientId = JsonPath.read(create("/api/v1/ingredients",
                "{\"name\":\"RecvBeans%d\",\"baseUnit\":\"g\"}".formatted(n)), "$.id");
        String supplierId = JsonPath.read(create("/api/v1/suppliers",
                "{\"name\":\"RecvRoaster%d\"}".formatted(n)), "$.id");
        String poJson = create("/api/v1/purchase-orders",
                "{\"supplierId\":\"%s\",\"lines\":[{\"ingredientId\":\"%s\",\"orderedQty\":2000,\"unitCost\":0.20}]}"
                        .formatted(supplierId, ingredientId));
        String poId = JsonPath.read(poJson, "$.id");
        String lineId = JsonPath.read(poJson, "$.lines[0].id");
        mockMvc.perform(authed(post("/api/v1/purchase-orders/" + poId + "/send"))).andExpect(status().isOk());

        // The delivery note says 2 kg at 400/kg: book 2000 g at 0.40/g.
        mockMvc.perform(authed(post("/api/v1/purchase-orders/" + poId + "/receive"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"receipts\":[{\"lineId\":\"%s\",\"receivedQty\":2,\"unit\":\"kg\",\"unitCostOverride\":400}]}"
                                .formatted(lineId)))
                .andExpect(status().isOk());

        assertThat(stockField(ingredientId, "quantityOnHand")).isEqualByComparingTo("2000");
        assertThat(stockField(ingredientId, "avgUnitCost.amount")).isEqualByComparingTo("0.40");
    }

    @Test
    void receive_rejectsAUnitOfTheWrongDimension() throws Exception {
        token = adminToken();
        branchId = ((List<String>) JsonPath.read(
                mockMvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, authHeader(token)))
                        .andReturn().getResponse().getContentAsString(), "$.branchIds")).get(0);
        int n = SEQ.incrementAndGet();

        String ingredientId = JsonPath.read(create("/api/v1/ingredients",
                "{\"name\":\"RecvMilk%d\",\"baseUnit\":\"g\"}".formatted(n)), "$.id");
        String supplierId = JsonPath.read(create("/api/v1/suppliers",
                "{\"name\":\"RecvDairy%d\"}".formatted(n)), "$.id");
        String poJson = create("/api/v1/purchase-orders",
                "{\"supplierId\":\"%s\",\"lines\":[{\"ingredientId\":\"%s\",\"orderedQty\":1000,\"unitCost\":0.10}]}"
                        .formatted(supplierId, ingredientId));
        String poId = JsonPath.read(poJson, "$.id");
        String lineId = JsonPath.read(poJson, "$.lines[0].id");
        mockMvc.perform(authed(post("/api/v1/purchase-orders/" + poId + "/send"))).andExpect(status().isOk());

        // Litres against a gram-based ingredient: reject, don't guess.
        mockMvc.perform(authed(post("/api/v1/purchase-orders/" + poId + "/receive"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"receipts\":[{\"lineId\":\"%s\",\"receivedQty\":1,\"unit\":\"l\"}]}"
                                .formatted(lineId)))
                .andExpect(status().isUnprocessableEntity());
    }
}
