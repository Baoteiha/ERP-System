package vn.essvn.erpcafe.sales;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.jayway.jsonpath.JsonPath;

import vn.essvn.erpcafe.inventory.persistence.StockMovementRepository;
import vn.essvn.erpcafe.organization.domain.Branch;
import vn.essvn.erpcafe.organization.persistence.BranchRepository;
import vn.essvn.erpcafe.support.AbstractIntegrationTest;

/**
 * Class: OrderPaymentLifecycleTest
 * Purpose: Proves payment, completion, cancel and void rules on orders, including two payments racing.
 */
class OrderPaymentLifecycleTest extends AbstractIntegrationTest {

    @Autowired
    private StockMovementRepository movements;

    @Autowired
    private BranchRepository branches;

    private String token;
    private String branchId;
    private String ingredientId;
    private String productId;

    /** Globally-unique suffix — all integration tests share one Testcontainers DB, so names must not collide. */
    private static String uniq() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    /** The seeded admin branch is shared by every test class, so a rate set here is put back afterwards. */
    private void setBranchTaxRate(String rate) {
        Branch branch = branches.findById(UUID.fromString(branchId)).orElseThrow();
        branch.setTaxRate(new BigDecimal(rate));
        branches.save(branch);
    }

    @AfterEach
    void restoreBranchTaxRate() {
        if (branchId != null) {
            setBranchTaxRate("0");
        }
    }

    private MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder b, String tok) {
        return b.header(HttpHeaders.AUTHORIZATION, authHeader(tok)).header("X-Branch-Id", branchId);
    }

    private String create(String url, String body) throws Exception {
        return mockMvc.perform(authed(post(url), token).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is2xxSuccessful())
                .andReturn().getResponse().getContentAsString();
    }

    /** Builds branch + coffee ingredient (1000g @0.20 in stock) + a Latte (25000, recipe 18g). Returns [ingredientId, productId]. */
    private String[] setup(String s) throws Exception {
        token = adminToken();
        branchId = ((List<String>) JsonPath.read(
                mockMvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, authHeader(token)))
                        .andReturn().getResponse().getContentAsString(), "$.branchIds")).get(0);

        String ingredientId = JsonPath.read(create("/api/v1/ingredients",
                "{\"name\":\"Coffee-%s\",\"baseUnit\":\"g\"}".formatted(s)), "$.id");
        String supplierId = JsonPath.read(create("/api/v1/suppliers", "{\"name\":\"Roaster-%s\"}".formatted(s)), "$.id");
        String poJson = create("/api/v1/purchase-orders",
                "{\"supplierId\":\"%s\",\"lines\":[{\"ingredientId\":\"%s\",\"orderedQty\":1000,\"unitCost\":0.20}]}"
                        .formatted(supplierId, ingredientId));
        String poId = JsonPath.read(poJson, "$.id");
        String lineId = JsonPath.read(poJson, "$.lines[0].id");
        mockMvc.perform(authed(post("/api/v1/purchase-orders/" + poId + "/send"), token)).andExpect(status().isOk());
        mockMvc.perform(authed(post("/api/v1/purchase-orders/" + poId + "/receive"), token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"receipts\":[{\"lineId\":\"%s\",\"receivedQty\":1000}]}".formatted(lineId)))
                .andExpect(status().isOk());

        String categoryId = JsonPath.read(create("/api/v1/categories",
                "{\"name\":\"Drinks-%s\",\"displayOrder\":1}".formatted(s)), "$.id");
        String productId = JsonPath.read(create("/api/v1/products",
                "{\"categoryId\":\"%s\",\"name\":\"Latte-%s\",\"sku\":\"LAT-%s\",\"basePrice\":25000}".formatted(categoryId, s, s)),
                "$.id");
        mockMvc.perform(authed(put("/api/v1/products/" + productId + "/recipe"), token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lines\":[{\"ingredientId\":\"%s\",\"quantity\":18,\"unit\":\"g\"}]}".formatted(ingredientId)))
                .andExpect(status().isOk());

        return new String[] { ingredientId, productId };
    }

    private BigDecimal stockQty(String ingredientId) throws Exception {
        String json = mockMvc.perform(get("/api/v1/stock").header(HttpHeaders.AUTHORIZATION, authHeader(token))
                        .header("X-Branch-Id", branchId))
                .andReturn().getResponse().getContentAsString();
        List<Object> vals = JsonPath.read(json, "$[?(@.ingredientId=='%s')].quantityOnHand".formatted(ingredientId));
        return new BigDecimal(String.valueOf(vals.get(0)));
    }

    private String order(String extra) throws Exception {
        return create("/api/v1/orders",
                "{\"orderType\":\"DINE_IN\"%s,\"lines\":[{\"productId\":\"%s\",\"quantity\":1}]}"
                        .formatted(extra, productId));
    }

    private org.springframework.test.web.servlet.ResultActions pay(String orderId, String method, int amount)
            throws Exception {
        return mockMvc.perform(authed(post("/api/v1/orders/" + orderId + "/payments"), token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"method\":\"%s\",\"amount\":%d}".formatted(method, amount)));
    }

    private void setup() throws Exception {
        String[] ids = setup(uniq());
        ingredientId = ids[0];
        productId = ids[1];
    }

    @Test
    void fullPayment_completesOrderDeductsStockAndSetsCogs() throws Exception {
        setup();
        String orderId = JsonPath.read(order(""), "$.id");
        pay(orderId, "CASH", 25000).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.cogsTotal").value(3.60));
        assertThat(stockQty(ingredientId)).isEqualByComparingTo("982");
    }

    @Test
    void overpayment_isRejectedAndNothingStored() throws Exception {
        setup();
        String orderId = JsonPath.read(order(""), "$.id");
        pay(orderId, "CASH", 30000).andExpect(status().is(422)).andExpect(jsonPath("$.code").value("BUSINESS_RULE"));
        mockMvc.perform(authed(get("/api/v1/orders/" + orderId), token))
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.payments.length()").value(0));
    }

    @Test
    void paymentOnCompletedOrder_isRejected() throws Exception {
        setup();
        String orderId = JsonPath.read(order(""), "$.id");
        pay(orderId, "CASH", 25000).andExpect(status().isOk());
        pay(orderId, "CASH", 1000).andExpect(status().is(422)).andExpect(jsonPath("$.code").value("BUSINESS_RULE"));
    }

    @Test
    void cancelAfterPartPayment_refundsTheTender() throws Exception {
        setup();
        String orderId = JsonPath.read(order(""), "$.id");
        pay(orderId, "CARD", 10000).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("OPEN"));
        mockMvc.perform(authed(post("/api/v1/orders/" + orderId + "/cancel"), token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.payments.length()").value(2))
                .andExpect(jsonPath("$.payments[1].type").value("REFUND"))
                .andExpect(jsonPath("$.payments[1].method").value("CARD"))
                .andExpect(jsonPath("$.payments[1].amount.amount").value(10000));
    }

    @Test
    void totals_subtotalIsGross_discountsCountOnce() throws Exception {
        setup();
        setBranchTaxRate("0.1");
        String json = create("/api/v1/orders",
                ("{\"orderType\":\"DINE_IN\",\"orderDiscount\":3000,"
                        + "\"lines\":[{\"productId\":\"%s\",\"quantity\":2,\"lineDiscount\":5000}]}")
                        .formatted(productId));
        assertThat(new BigDecimal(String.valueOf((Object) JsonPath.read(json, "$.subtotal")))).isEqualByComparingTo("50000");
        assertThat(new BigDecimal(String.valueOf((Object) JsonPath.read(json, "$.discountTotal")))).isEqualByComparingTo("8000");
        assertThat(new BigDecimal(String.valueOf((Object) JsonPath.read(json, "$.taxTotal")))).isEqualByComparingTo("4200");
        assertThat(new BigDecimal(String.valueOf((Object) JsonPath.read(json, "$.grandTotal")))).isEqualByComparingTo("46200");
    }

    @Test
    void discountAboveGross_isRejectedWith422() throws Exception {
        setup();
        mockMvc.perform(authed(post("/api/v1/orders"), token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderType\":\"DINE_IN\",\"lines\":[{\"productId\":\"%s\",\"quantity\":1,\"lineDiscount\":25001}]}"
                                .formatted(productId)))
                .andExpect(status().is(422)).andExpect(jsonPath("$.code").value("BUSINESS_RULE"));
    }

    @Test
    void zeroTotalOrder_completesAtCreation() throws Exception {
        setup();
        String json = order(",\"orderDiscount\":25000");
        assertThat((String) JsonPath.read(json, "$.status")).isEqualTo("COMPLETED");
        assertThat(stockQty(ingredientId)).isEqualByComparingTo("982");
    }

    @Test
    void completeOnOpenOrder_isRejectedWithoutStockMovements() throws Exception {
        setup();
        String orderId = JsonPath.read(order(""), "$.id");
        mockMvc.perform(authed(post("/api/v1/orders/" + orderId + "/complete"), token))
                .andExpect(status().is(422)).andExpect(jsonPath("$.code").value("BUSINESS_RULE"));
        assertThat(movements.findByRefTypeAndRefId("ORDER", UUID.fromString(orderId))).isEmpty();
        assertThat(stockQty(ingredientId)).isEqualByComparingTo("1000");
    }

    @Test
    void voidOfCompletedOrder_refundsTheGrandTotal() throws Exception {
        setup();
        String orderId = JsonPath.read(order(""), "$.id");
        pay(orderId, "CASH", 25000).andExpect(status().isOk());
        mockMvc.perform(authed(post("/api/v1/orders/" + orderId + "/void"), token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("VOID"))
                .andExpect(jsonPath("$.payments.length()").value(2))
                .andExpect(jsonPath("$.payments[1].type").value("REFUND"))
                .andExpect(jsonPath("$.payments[1].amount.amount").value(25000));
        assertThat(stockQty(ingredientId)).isEqualByComparingTo("1000");
    }

    @Test
    void twoConcurrentFullPayments_oneWinsAndStockIsDeductedOnce() throws Exception {
        setup();
        String orderId = JsonPath.read(order(""), "$.id");
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            results.add(pool.submit(() -> {
                start.await();
                return pay(orderId, "CASH", 25000).andReturn().getResponse().getStatus();
            }));
        }
        start.countDown();
        List<Integer> codes = new ArrayList<>();
        try {
            for (Future<Integer> f : results) {
                codes.add(f.get(30, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(codes).containsOnlyOnce(200);
        assertThat(codes).filteredOn(c -> c != 200).allMatch(c -> c == 409 || c == 422);
        assertThat(movements.findByRefTypeAndRefId("ORDER", UUID.fromString(orderId))).hasSize(1);
        assertThat(stockQty(ingredientId)).isEqualByComparingTo("982");
        // the loser's payment was rolled back with its transaction
        mockMvc.perform(authed(get("/api/v1/orders/" + orderId), token))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.payments.length()").value(1));
    }
}
