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

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.jayway.jsonpath.JsonPath;

import vn.essvn.erpcafe.support.AbstractIntegrationTest;

/**
 * Class: PackReceivingTest
 * Purpose: Receiving in packs puts usable units into stock at the price paid and records damage, extras and the loss.
 */
class PackReceivingTest extends AbstractIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired
    @Qualifier("ownerDataSource")
    private DataSource ownerDataSource;

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
        straws = id(send(post("/api/v1/ingredients"), "{\"name\":\"RecvStraws%d\",\"baseUnit\":\"pc\"}".formatted(n)));
        shop = id(send(post("/api/v1/suppliers"), "{\"name\":\"RecvShop%d\"}".formatted(n)));
        box = id(send(post("/api/v1/items"), ("{\"name\":\"Ống hút hộp 100\",\"supplierId\":\"%s\",\"ingredientId\":\"%s\","
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

    /** A sent order for {@code qty} of the item at {@code cost} per ordering unit; returns the PO json. */
    private String sentOrder(String itemId, String qty, String cost) throws Exception {
        String po = body(send(post("/api/v1/purchase-orders"),
                "{\"supplierId\":\"%s\",\"lines\":[{\"itemId\":\"%s\",\"orderedQty\":%s,\"unitCost\":%s}]}"
                        .formatted(shop, itemId, qty, cost)));
        mockMvc.perform(authed(post("/api/v1/purchase-orders/" + JsonPath.read(po, "$.id") + "/send")))
                .andExpect(status().isOk());
        return po;
    }

    private ResultActions receive(String po, String receiptFields) throws Exception {
        return send(post("/api/v1/purchase-orders/" + JsonPath.read(po, "$.id") + "/receive"),
                "{\"receipts\":[{\"lineId\":\"%s\",%s}]}".formatted((String) JsonPath.read(po, "$.lines[0].id"), receiptFields));
    }

    private BigDecimal stock(String field) throws Exception {
        String json = body(mockMvc.perform(authed(get("/api/v1/stock"))));
        List<Object> vals = JsonPath.read(json, "$[?(@.ingredientId=='%s')].%s".formatted(straws, field));
        return vals.isEmpty() ? null : new BigDecimal(String.valueOf(vals.get(0)));
    }

    private static BigDecimal num(String json, String path) {
        return new BigDecimal(String.valueOf((Object) JsonPath.read(json, path)));
    }

    /** 200 pc already on hand at 400 each, bought loose from the same shop. */
    private void twoHundredOnHandAt400() throws Exception {
        receive(sentOrder(itemFor(token, branchId, shop, straws, "pc"), "200", "400"), "\"receivedQty\":200")
                .andExpect(status().isOk());
    }

    @Test
    void fiveBoxes_enterFiveHundredAtTheBoxPriceDividedByItsSize() throws Exception {
        String after = body(receive(sentOrder(box, "5", "45000"), "\"receivedQty\":5"));

        assertThat(stock("quantityOnHand")).isEqualByComparingTo("500");
        assertThat(stock("avgUnitCost.amount")).isEqualByComparingTo("450");
        assertThat((String) JsonPath.read(after, "$.status")).isEqualTo("RECEIVED");
        assertThat((String) JsonPath.read(after, "$.receipts[0].kind")).isEqualTo("DELIVERY");
        assertThat(num(after, "$.receipts[0].lines[0].deliveredQty")).isEqualByComparingTo("5");
        assertThat((String) JsonPath.read(after, "$.receipts[0].receivedBy")).isNotBlank();
    }

    @Test
    void damagedUnits_neverEnterStock_andTheLossIsAtThePricePaid() throws Exception {
        twoHundredOnHandAt400();

        String after = body(receive(sentOrder(box, "5", "45000"),
                "\"receivedQty\":5,\"damagedQty\":40,\"note\":\"one box torn\""));

        assertThat(stock("quantityOnHand")).isEqualByComparingTo("660");
        assertThat(stock("avgUnitCost.amount")).isEqualByComparingTo("434.8485");
        assertThat((String) JsonPath.read(after, "$.status")).isEqualTo("RECEIVED");
        assertThat(num(after, "$.receipts[0].lines[0].lossQty")).isEqualByComparingTo("40");
        assertThat(num(after, "$.receipts[0].lines[0].lossAmount.amount")).isEqualByComparingTo("18000");
        assertThat((String) JsonPath.read(after, "$.receipts[0].lines[0].note")).isEqualTo("one box torn");
    }

    @Test
    void costPerUnit_isStoredAtMoneyScaleHalfEven() throws Exception {
        String thirtyTwo = id(send(post("/api/v1/items"), ("{\"name\":\"Ống hút hộp 32\",\"supplierId\":\"%s\",\"ingredientId\":\"%s\","
                + "\"unit\":\"pc\",\"packLabel\":\"hộp\",\"packSize\":32}").formatted(shop, straws)));

        String after = body(receive(sentOrder(thirtyTwo, "1", "45001"), "\"receivedQty\":1"));

        assertThat(num(after, "$.receipts[0].lines[0].costPerUnit")).isEqualByComparingTo("1406.2812");
        assertThat(stock("avgUnitCost.amount")).isEqualByComparingTo("1406.2812");
    }

    @Test
    void shortDelivery_leavesTheLineOutstanding() throws Exception {
        String after = body(receive(sentOrder(box, "5", "45000"), "\"receivedQty\":4"));

        assertThat((String) JsonPath.read(after, "$.status")).isEqualTo("PARTIALLY_RECEIVED");
        assertThat(num(after, "$.lines[0].receivedQty")).isEqualByComparingTo("4");
        assertThat(stock("quantityOnHand")).isEqualByComparingTo("400");
    }

    @Test
    void chargedOverDelivery_growsTheOrder() throws Exception {
        String after = body(receive(sentOrder(box, "5", "45000"), "\"receivedQty\":6,\"overDeliveryKind\":\"CHARGED\""));

        assertThat(num(after, "$.lines[0].orderedQty")).isEqualByComparingTo("6");
        assertThat((String) JsonPath.read(after, "$.status")).isEqualTo("RECEIVED");
        assertThat(stock("quantityOnHand")).isEqualByComparingTo("600");
        assertThat(stock("avgUnitCost.amount")).isEqualByComparingTo("450");
    }

    @Test
    void freeOverDelivery_entersAtZero_andTheOrderStays() throws Exception {
        String after = body(receive(sentOrder(box, "5", "45000"), "\"receivedQty\":6,\"overDeliveryKind\":\"FREE\""));

        assertThat(num(after, "$.lines[0].orderedQty")).isEqualByComparingTo("5");
        assertThat(stock("quantityOnHand")).isEqualByComparingTo("600");
        assertThat(stock("avgUnitCost.amount")).isEqualByComparingTo("375");
        assertThat((String) JsonPath.read(after, "$.receipts[0].lines[0].overDeliveryKind")).isEqualTo("FREE");
    }

    @Test
    void freeAndDamaged_damageCountsAgainstThePaidUnitsFirst() throws Exception {
        String after = body(receive(sentOrder(box, "5", "45000"),
                "\"receivedQty\":6,\"overDeliveryKind\":\"FREE\",\"damagedQty\":150"));

        assertThat(stock("quantityOnHand")).isEqualByComparingTo("450");
        assertThat(num(after, "$.receipts[0].lines[0].lossAmount.amount")).isEqualByComparingTo("67500");
    }

    @Test
    void wholeBoxDamaged_entersNothing_butCountsTheBox() throws Exception {
        String after = body(receive(sentOrder(box, "1", "45000"), "\"receivedQty\":1,\"damagedQty\":100"));

        assertThat(stock("quantityOnHand")).isNull();
        assertThat((String) JsonPath.read(after, "$.status")).isEqualTo("RECEIVED");
        assertThat(num(after, "$.receipts[0].lines[0].lossAmount.amount")).isEqualByComparingTo("45000");
    }

    @Test
    void actualPricePerPack_setsTheStockCost() throws Exception {
        receive(sentOrder(box, "5", "45000"), "\"receivedQty\":5,\"unitCostOverride\":50000").andExpect(status().isOk());

        assertThat(stock("avgUnitCost.amount")).isEqualByComparingTo("500");
    }

    @Test
    void refusals_leaveStockAndTheOrderUntouched() throws Exception {
        String po = sentOrder(box, "5", "45000");
        for (String fields : List.of("\"receivedQty\":6", "\"receivedQty\":5,\"overDeliveryKind\":\"FREE\"",
                "\"receivedQty\":5,\"damagedQty\":501", "\"receivedQty\":2.5", "\"receivedQty\":5,\"unit\":\"pc\"")) {
            receive(po, fields).andExpect(status().isUnprocessableEntity());
        }
        String now = body(mockMvc.perform(authed(get("/api/v1/purchase-orders/" + JsonPath.read(po, "$.id")))));
        assertThat((String) JsonPath.read(now, "$.status")).isEqualTo("SENT");
        assertThat((List<?>) JsonPath.read(now, "$.receipts")).isEmpty();
        assertThat(stock("quantityOnHand")).isNull();
    }

    @Test
    void secondReceipt_onAFullyReceivedOrder_isRefused() throws Exception {
        String po = sentOrder(box, "5", "45000");
        receive(po, "\"receivedQty\":5").andExpect(status().isOk());

        receive(po, "\"receivedQty\":5").andExpect(status().isUnprocessableEntity());
        assertThat(stock("quantityOnHand")).isEqualByComparingTo("500");
    }

    @Test
    void packEditedAfterOrdering_receivesWithTheOrdersPack() throws Exception {
        String po = sentOrder(box, "5", "45000");
        send(put("/api/v1/items/" + box), ("{\"name\":\"Ống hút hộp 50\",\"supplierId\":\"%s\",\"ingredientId\":\"%s\","
                + "\"unit\":\"pc\",\"packLabel\":\"hộp\",\"packSize\":50,\"version\":0}").formatted(shop, straws))
                .andExpect(status().isOk());

        receive(po, "\"receivedQty\":5").andExpect(status().isOk());

        assertThat(stock("quantityOnHand")).isEqualByComparingTo("500");
    }

    @Test
    void legacyIngredientLine_stillReceives() throws Exception {
        String po = sentOrder(itemFor(token, branchId, shop, straws, "pc"), "10", "400");
        new JdbcTemplate(ownerDataSource).update("UPDATE purchase_order_line SET item_id = NULL WHERE id = ?::uuid",
                (String) JsonPath.read(po, "$.lines[0].id"));

        receive(po, "\"receivedQty\":10").andExpect(status().isOk());

        assertThat(stock("quantityOnHand")).isEqualByComparingTo("10");
    }

    @Test
    void movement_pointsAtTheReceipt_andTheReceiptLeadsBackToTheOrder() throws Exception {
        String po = sentOrder(box, "5", "45000");
        String after = body(receive(po, "\"receivedQty\":5"));
        String receiptId = JsonPath.read(after, "$.receipts[0].id");

        String movements = body(mockMvc.perform(authed(get("/api/v1/stock/movements").param("ingredientId", straws))));
        assertThat((String) JsonPath.read(movements, "$.content[0].refType")).isEqualTo("GOODS_RECEIPT");
        assertThat((String) JsonPath.read(movements, "$.content[0].refId")).isEqualTo(receiptId);

        mockMvc.perform(authed(get("/api/v1/purchase-orders/by-receipt/" + receiptId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value((String) JsonPath.read(po, "$.id")));
        mockMvc.perform(authed(get("/api/v1/purchase-orders/by-receipt/" + java.util.UUID.randomUUID())))
                .andExpect(status().isNotFound());
    }
}
