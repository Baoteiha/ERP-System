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
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.jayway.jsonpath.JsonPath;

import vn.essvn.erpcafe.organization.domain.Branch;
import vn.essvn.erpcafe.organization.persistence.BranchRepository;
import vn.essvn.erpcafe.sales.persistence.OrderRepository;
import vn.essvn.erpcafe.support.AbstractIntegrationTest;

/**
 * Class: QuoteAndIdempotencyTest
 * Purpose: Proves the branch menu prices like the till, a quote equals the order it previews, and a repeated create is one order.
 */
class QuoteAndIdempotencyTest extends AbstractIntegrationTest {

    @Autowired
    private OrderRepository orders;

    @Autowired
    private BranchRepository branches;

    private String token;
    private String branchId;

    @BeforeEach
    void login() throws Exception {
        token = adminToken();
        branchId = ((List<String>) JsonPath.read(
                mockMvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, authHeader(token)))
                        .andReturn().getResponse().getContentAsString(), "$.branchIds")).get(0);
    }

    /** The seeded admin branch is shared by every test class, so a rate set here is put back afterwards. */
    @AfterEach
    void restoreBranchTaxRate() {
        setBranchTaxRate("0");
    }

    private void setBranchTaxRate(String rate) {
        Branch branch = branches.findById(UUID.fromString(branchId)).orElseThrow();
        branch.setTaxRate(new BigDecimal(rate));
        branches.save(branch);
    }

    private static String uniq() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder b) {
        return b.header(HttpHeaders.AUTHORIZATION, authHeader(token)).header("X-Branch-Id", branchId);
    }

    private String create(String url, String body) throws Exception {
        return mockMvc.perform(authed(post(url)).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is2xxSuccessful())
                .andReturn().getResponse().getContentAsString();
    }

    private ResultActions send(MockHttpServletRequestBuilder b, String body) throws Exception {
        return mockMvc.perform(authed(b).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private String newProduct(String s, int price) throws Exception {
        String categoryId = JsonPath.read(create("/api/v1/categories",
                "{\"name\":\"Cat-%s\",\"displayOrder\":1}".formatted(s)), "$.id");
        return JsonPath.read(create("/api/v1/products",
                "{\"categoryId\":\"%s\",\"name\":\"Tea-%s\",\"sku\":\"TEA-%s\",\"basePrice\":%d}"
                        .formatted(categoryId, s, s, price)), "$.id");
    }

    private void setAvailability(String productId, boolean available, String override) throws Exception {
        send(put("/api/v1/products/" + productId + "/availability"),
                "{\"branchId\":\"%s\",\"available\":%s,\"priceOverride\":%s}".formatted(branchId, available, override))
                .andExpect(status().isOk());
    }

    private static BigDecimal dec(String json, String path) {
        return new BigDecimal(String.valueOf((Object) JsonPath.read(json, path)));
    }

    private static String orderBody(String productId, String clientRequestId) {
        return "{\"orderType\":\"DINE_IN\",\"orderDiscount\":1000%s,\"lines\":[{\"productId\":\"%s\",\"quantity\":3,\"lineDiscount\":2500}]}"
                .formatted(clientRequestId == null ? "" : ",\"clientRequestId\":\"" + clientRequestId + "\"", productId);
    }

    @Test
    void menu_pricesWithTheBranchOverrideAndOmitsWhatTheBranchDoesNotSell() throws Exception {
        String s = uniq();
        String overridden = newProduct("o" + s, 25000);
        setAvailability(overridden, true, "28000");
        String hidden = newProduct("h" + s, 25000);
        setAvailability(hidden, false, "null");
        String plain = newProduct("p" + s, 21000);

        String groupId = JsonPath.read(create("/api/v1/modifier-groups",
                "{\"name\":\"Grp-%s\",\"minSelect\":0,\"maxSelect\":2}".formatted(s)), "$.id");
        create("/api/v1/modifier-groups/" + groupId + "/modifiers",
                "{\"name\":\"On-%s\",\"priceDelta\":5000,\"displayOrder\":1}".formatted(s));
        String offId = JsonPath.read(create("/api/v1/modifier-groups/" + groupId + "/modifiers",
                "{\"name\":\"Off-%s\",\"priceDelta\":7000,\"displayOrder\":2}".formatted(s)), "$.id");
        send(put("/api/v1/modifier-groups/" + groupId + "/modifiers/" + offId),
                "{\"name\":\"Off-%s\",\"priceDelta\":7000,\"active\":false,\"displayOrder\":2}".formatted(s))
                .andExpect(status().isOk());
        send(put("/api/v1/products/" + overridden + "/modifier-groups"),
                "{\"modifierGroupIds\":[\"%s\"]}".formatted(groupId)).andExpect(status().isOk());

        String json = mockMvc.perform(authed(get("/api/v1/menu"))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        List<Object> price = JsonPath.read(json, "$[?(@.id=='%s')].effectivePrice.amount".formatted(overridden));
        assertThat(new BigDecimal(String.valueOf(price.get(0)))).isEqualByComparingTo("28000");
        List<Object> basePrice = JsonPath.read(json, "$[?(@.id=='%s')].effectivePrice.amount".formatted(plain));
        assertThat(new BigDecimal(String.valueOf(basePrice.get(0)))).isEqualByComparingTo("21000");
        assertThat((List<Object>) JsonPath.read(json, "$[?(@.id=='%s')]".formatted(hidden))).isEmpty();
        // the menu quotes what the till charges for a bare item
        assertThat(dec(mockMvc.perform(authed(get("/api/v1/products/" + overridden + "/price?branchId=" + branchId)))
                .andReturn().getResponse().getContentAsString(), "$.price.amount")).isEqualByComparingTo("28000");
        List<Object> modifierNames = JsonPath.read(json,
                "$[?(@.id=='%s')].modifierGroups[0].modifiers[*].name".formatted(overridden));
        assertThat(modifierNames).containsExactly("On-" + s);
    }

    @Test
    void quote_equalsTheOrderLaterCreatedAndStoresNothing() throws Exception {
        String productId = newProduct(uniq(), 25000);
        setBranchTaxRate("0.085");
        long before = orders.count();

        String quote = create("/api/v1/orders/quote", orderBody(productId, null));
        assertThat(orders.count()).isEqualTo(before);

        String created = create("/api/v1/orders", orderBody(productId, UUID.randomUUID().toString()));
        for (String field : List.of("subtotal", "discountTotal", "taxTotal", "grandTotal")) {
            assertThat(dec(quote, "$." + field)).as(field).isEqualByComparingTo(dec(created, "$." + field));
        }
        assertThat(dec(quote, "$.lines[0].lineTotal.amount")).isEqualByComparingTo("72500");
        assertThat(dec(quote, "$.grandTotal")).isEqualByComparingTo(
                dec(quote, "$.subtotal").subtract(dec(quote, "$.discountTotal")).add(dec(quote, "$.taxTotal")));
    }

    @Test
    void quote_ofAnInvalidLine_isRefusedLikeCreate() throws Exception {
        String productId = newProduct(uniq(), 25000);
        setAvailability(productId, false, "null");
        send(post("/api/v1/orders/quote"), orderBody(productId, null))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("BUSINESS_RULE"));
    }

    @Test
    void repeatedCreate_withOneClientRequestId_returnsTheSameOrder() throws Exception {
        String productId = newProduct(uniq(), 25000);
        String requestId = UUID.randomUUID().toString();

        String first = mockMvc.perform(authed(post("/api/v1/orders")).contentType(MediaType.APPLICATION_JSON)
                        .content(orderBody(productId, requestId)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String second = mockMvc.perform(authed(post("/api/v1/orders")).contentType(MediaType.APPLICATION_JSON)
                        .content(orderBody(productId, requestId)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat((String) JsonPath.read(second, "$.id")).isEqualTo(JsonPath.read(first, "$.id"));
        assertThat(orders.findAll().stream()
                .filter(o -> requestId.equals(String.valueOf(o.getClientRequestId())))).hasSize(1);
    }

    @Test
    void twoCreatesAtOnce_withOneClientRequestId_makeOneOrder() throws Exception {
        String productId = newProduct(uniq(), 25000);
        String requestId = UUID.randomUUID().toString();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<int[]>> results = new ArrayList<>();
            List<String> ids = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                Callable<int[]> call = () -> {
                    go.await();
                    var response = mockMvc.perform(authed(post("/api/v1/orders"))
                                    .contentType(MediaType.APPLICATION_JSON).content(orderBody(productId, requestId)))
                            .andReturn().getResponse();
                    synchronized (ids) {
                        ids.add(response.getStatus() < 300 ? JsonPath.read(response.getContentAsString(), "$.id") : "none");
                    }
                    return new int[] { response.getStatus() };
                };
                results.add(pool.submit(call));
            }
            go.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<int[]> f : results) {
                statuses.add(f.get(30, TimeUnit.SECONDS)[0]);
            }
            assertThat(statuses).containsExactlyInAnyOrder(200, 201);
            assertThat(ids).hasSize(2).doesNotContain("none").containsOnly(ids.get(0));
        } finally {
            pool.shutdownNow();
        }
        assertThat(orders.findAll().stream()
                .filter(o -> requestId.equals(String.valueOf(o.getClientRequestId())))).hasSize(1);
    }

    @Test
    void createWithoutAClientRequestId_stillMakesADistinctOrderEachTime() throws Exception {
        String productId = newProduct(uniq(), 25000);
        String a = create("/api/v1/orders", orderBody(productId, null));
        String b = create("/api/v1/orders", orderBody(productId, null));
        assertThat((String) JsonPath.read(a, "$.id")).isNotEqualTo(JsonPath.read(b, "$.id"));
    }
}
