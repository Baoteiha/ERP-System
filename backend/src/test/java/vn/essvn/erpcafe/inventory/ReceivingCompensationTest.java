package vn.essvn.erpcafe.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.jayway.jsonpath.JsonPath;

import vn.essvn.erpcafe.support.AbstractIntegrationTest;

/**
 * Class: ReceivingCompensationTest
 * Purpose: A supplier's replacement enters at the lost units' price and clears the loss, never beyond what was lost.
 */
class ReceivingCompensationTest extends AbstractIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger();

    private String token;
    private String branchId;
    private String straws;
    private String shop;
    private String box;

    @BeforeEach
    void setUp() throws Exception {
        token = adminToken();
        branchId = ((List<String>) JsonPath.read(
                mockMvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, authHeader(token)))
                        .andReturn().getResponse().getContentAsString(), "$.branchIds")).get(0);
        int n = SEQ.incrementAndGet();
        straws = id(send(post("/api/v1/ingredients"), "{\"name\":\"CompStraws%d\",\"baseUnit\":\"pc\"}".formatted(n)));
        shop = id(send(post("/api/v1/suppliers"), "{\"name\":\"CompShop%d\"}".formatted(n)));
        box = id(send(post("/api/v1/items"), ("{\"name\":\"Box\",\"supplierId\":\"%s\",\"ingredientId\":\"%s\","
                + "\"unit\":\"pc\",\"packLabel\":\"hộp\",\"packSize\":100}").formatted(shop, straws)));
    }

    private MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder b) {
        return b.header(HttpHeaders.AUTHORIZATION, authHeader(token)).header("X-Branch-Id", branchId);
    }

    private ResultActions send(MockHttpServletRequestBuilder b, String body) throws Exception {
        return mockMvc.perform(authed(b).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private static String body(ResultActions r) throws Exception {
        return r.andExpect(status().is2xxSuccessful()).andReturn().getResponse().getContentAsString();
    }

    private static String id(ResultActions r) throws Exception {
        return JsonPath.read(body(r), "$.id");
    }

    private String sentOrder(String itemId, String qty, String cost) throws Exception {
        String po = body(send(post("/api/v1/purchase-orders"),
                "{\"supplierId\":\"%s\",\"lines\":[{\"itemId\":\"%s\",\"orderedQty\":%s,\"unitCost\":%s}]}"
                        .formatted(shop, itemId, qty, cost)));
        mockMvc.perform(authed(post("/api/v1/purchase-orders/" + JsonPath.read(po, "$.id") + "/send")))
                .andExpect(status().isOk());
        return po;
    }

    private String receive(String po, String fields) throws Exception {
        return body(send(post("/api/v1/purchase-orders/" + JsonPath.read(po, "$.id") + "/receive"),
                "{\"receipts\":[{\"lineId\":\"%s\",%s}]}".formatted((String) JsonPath.read(po, "$.lines[0].id"), fields)));
    }

    private ResultActions compensate(String po, String lines) throws Exception {
        return send(post("/api/v1/purchase-orders/" + JsonPath.read(po, "$.id") + "/compensations"),
                "{\"note\":\"supplier sent 40\",\"lines\":[%s]}".formatted(lines));
    }

    private BigDecimal stock(String field) throws Exception {
        String json = body(mockMvc.perform(authed(get("/api/v1/stock"))));
        List<Object> vals = JsonPath.read(json, "$[?(@.ingredientId=='%s')].%s".formatted(straws, field));
        return vals.isEmpty() ? null : new BigDecimal(String.valueOf(vals.get(0)));
    }

    private static BigDecimal num(String json, String path) {
        return new BigDecimal(String.valueOf((Object) JsonPath.read(json, path)));
    }

    /** The worked example: 200 on hand at 400, then 5 boxes at 45,000 with 40 pieces damaged. */
    private String damagedDelivery() throws Exception {
        String loose = itemFor(token, branchId, shop, straws, "pc");
        receive(sentOrder(loose, "200", "400"), "\"receivedQty\":200");
        String po = sentOrder(box, "5", "45000");
        receive(po, "\"receivedQty\":5,\"damagedQty\":40");
        return po;
    }

    private static String lostLine(String poJson) {
        return JsonPath.read(poJson, "$.receipts[0].lines[0].id");
    }

    @Test
    void replacement_entersAtTheLostUnitsPrice_andClearsTheLoss() throws Exception {
        String po = damagedDelivery();
        String lost = lostLine(body(mockMvc.perform(authed(get("/api/v1/purchase-orders/" + JsonPath.read(po, "$.id"))))));

        String after = body(compensate(po, "{\"receiptLineId\":\"%s\",\"quantity\":40}".formatted(lost)));

        assertThat(stock("quantityOnHand")).isEqualByComparingTo("700");
        assertThat(stock("avgUnitCost.amount")).isEqualByComparingTo("435.7143");
        assertThat(num(after, "$.receipts[0].lines[0].lossAmount.amount")).isEqualByComparingTo("0");
        assertThat(num(after, "$.receipts[0].lines[0].compensatedQty")).isEqualByComparingTo("40");
        assertThat((String) JsonPath.read(after, "$.receipts[1].kind")).isEqualTo("COMPENSATION");
        assertThat((String) JsonPath.read(after, "$.receipts[1].lines[0].compensatesLineId")).isEqualTo(lost);
    }

    @Test
    void replacement_cannotExceedTheRemainingLoss() throws Exception {
        String po = damagedDelivery();
        String lost = lostLine(body(mockMvc.perform(authed(get("/api/v1/purchase-orders/" + JsonPath.read(po, "$.id"))))));

        compensate(po, "{\"receiptLineId\":\"%s\",\"quantity\":41}".formatted(lost)).andExpect(status().isUnprocessableEntity());
        compensate(po, "{\"receiptLineId\":\"%s\",\"quantity\":30}".formatted(lost)).andExpect(status().isOk());
        compensate(po, "{\"receiptLineId\":\"%s\",\"quantity\":11}".formatted(lost)).andExpect(status().isUnprocessableEntity());

        assertThat(stock("quantityOnHand")).isEqualByComparingTo("690");
    }

    @Test
    void twoReplacementsForOneLine_areCappedTogether_andNothingEnters() throws Exception {
        String po = damagedDelivery();
        String lost = lostLine(body(mockMvc.perform(authed(get("/api/v1/purchase-orders/" + JsonPath.read(po, "$.id"))))));

        compensate(po, "{\"receiptLineId\":\"%s\",\"quantity\":30},{\"receiptLineId\":\"%s\",\"quantity\":30}"
                .formatted(lost, lost)).andExpect(status().isUnprocessableEntity());

        assertThat(stock("quantityOnHand")).isEqualByComparingTo("660");
    }

    @Test
    void replacement_onACancelledOrder_isRefused() throws Exception {
        String po = sentOrder(box, "5", "45000");
        receive(po, "\"receivedQty\":4,\"damagedQty\":40");
        mockMvc.perform(authed(post("/api/v1/purchase-orders/" + JsonPath.read(po, "$.id") + "/cancel")))
                .andExpect(status().isOk());
        String lost = lostLine(body(mockMvc.perform(authed(get("/api/v1/purchase-orders/" + JsonPath.read(po, "$.id"))))));

        compensate(po, "{\"receiptLineId\":\"%s\",\"quantity\":40}".formatted(lost)).andExpect(status().isUnprocessableEntity());
    }

    @Test
    void unknownReceiptLine_isNotFound_andAFreeGiftsDamageIsNotReplaced() throws Exception {
        String po = sentOrder(box, "5", "45000");
        String after = receive(po, "\"receivedQty\":6,\"overDeliveryKind\":\"FREE\",\"damagedQty\":0");

        compensate(po, "{\"receiptLineId\":\"%s\",\"quantity\":1}".formatted(UUID.randomUUID())).andExpect(status().isNotFound());
        compensate(po, "{\"receiptLineId\":\"%s\",\"quantity\":1}".formatted(lostLine(after))).andExpect(status().isUnprocessableEntity());
    }
}
