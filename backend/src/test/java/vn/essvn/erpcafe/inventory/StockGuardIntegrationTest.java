package vn.essvn.erpcafe.inventory;

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

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.jayway.jsonpath.JsonPath;

import vn.essvn.erpcafe.common.context.TenantContext;
import vn.essvn.erpcafe.inventory.api.StockLine;
import vn.essvn.erpcafe.inventory.application.StockLedgerService;
import vn.essvn.erpcafe.support.AbstractIntegrationTest;

/**
 * Class: StockGuardIntegrationTest
 * Purpose: Proves a sale that would take any ingredient below zero is refused whole, while a sale within stock completes.
 */
class StockGuardIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private StockLedgerService ledger;

    /** The schema owner's SQL: reads movements and ledger claims past row-level security. */
    private JdbcTemplate db;

    private String token;
    private String branchId;
    private UUID companyId;

    @Autowired
    void ownerSql(@Qualifier("ownerDataSource") DataSource ownerDataSource) {
        db = new JdbcTemplate(ownerDataSource);
    }

    @BeforeEach
    void signIn() throws Exception {
        token = adminToken();
        String me = mockMvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, authHeader(token)))
                .andReturn().getResponse().getContentAsString();
        branchId = ((List<String>) JsonPath.read(me, "$.branchIds")).get(0);
        companyId = UUID.fromString(JsonPath.read(me, "$.companyId"));
    }

    /** Globally-unique suffix — all integration tests share one Testcontainers DB, so names must not collide. */
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

    private String ingredient(String name, String baseUnit) throws Exception {
        return JsonPath.read(create("/api/v1/ingredients",
                "{\"name\":\"%s\",\"baseUnit\":\"%s\"}".formatted(name, baseUnit)), "$.id");
    }

    /** A stock count correction at the admin's branch, in the ingredient's base unit. */
    private void adjust(String ingredientId, String delta) throws Exception {
        create("/api/v1/stock/adjust", "{\"ingredientId\":\"%s\",\"quantityDelta\":%s}".formatted(ingredientId, delta));
    }

    /** One recipe line as the recipe endpoint takes it. */
    private static String uses(String ingredientId, String quantity, String unit) {
        return "{\"ingredientId\":\"%s\",\"quantity\":%s,\"unit\":\"%s\"}".formatted(ingredientId, quantity, unit);
    }

    /** A 10,000 product using the given recipe lines; no lines means no recipe at all. Returns its id. */
    private String product(String... recipeLines) throws Exception {
        String s = uniq();
        String categoryId = JsonPath.read(create("/api/v1/categories",
                "{\"name\":\"Guard-%s\",\"displayOrder\":1}".formatted(s)), "$.id");
        String productId = JsonPath.read(create("/api/v1/products",
                "{\"categoryId\":\"%s\",\"name\":\"Guarded-%s\",\"sku\":\"GRD-%s\",\"basePrice\":10000}"
                        .formatted(categoryId, s, s)), "$.id");
        if (recipeLines.length > 0) {
            mockMvc.perform(authed(put("/api/v1/products/" + productId + "/recipe"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"lines\":[%s]}".formatted(String.join(",", recipeLines))))
                    .andExpect(status().isOk());
        }
        return productId;
    }

    /** An open order for {@code quantity} of the product; returns the order JSON. */
    private String order(String productId, int quantity) throws Exception {
        return create("/api/v1/orders", "{\"orderType\":\"TAKEAWAY\",\"lines\":[{\"productId\":\"%s\",\"quantity\":%d}]}"
                .formatted(productId, quantity));
    }

    /** Pays the order's whole grand total in cash, which completes it when stock allows. */
    private ResultActions pay(String orderJson) throws Exception {
        return mockMvc.perform(authed(post("/api/v1/orders/" + JsonPath.read(orderJson, "$.id") + "/payments"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"method\":\"CASH\",\"amount\":%s}".formatted(num(orderJson, "$.grandTotal").toPlainString())));
    }

    /** Reads a numeric JSON field as BigDecimal; the (Object) cast picks String.valueOf(Object). */
    private static BigDecimal num(String json, String path) {
        return new BigDecimal(String.valueOf((Object) JsonPath.read(json, path)));
    }

    /** On-hand at the admin's branch, or null when the branch has no stock row for the ingredient. */
    private BigDecimal onHand(String ingredientId) throws Exception {
        String json = mockMvc.perform(authed(get("/api/v1/stock"))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<Object> vals = JsonPath.read(json, "$[?(@.ingredientId=='%s')].quantityOnHand".formatted(ingredientId));
        return vals.isEmpty() ? null : new BigDecimal(String.valueOf(vals.get(0)));
    }

    private int depletions(String orderId) {
        return db.queryForObject("SELECT count(*) FROM stock_movement WHERE ref_type = 'ORDER' AND ref_id = ?::uuid"
                + " AND type = 'SALE_DEPLETION'", Integer.class, orderId);
    }

    private int ledgerClaims(String orderId) {
        return db.queryForObject("SELECT count(*) FROM ledger_reference WHERE ref_type = 'ORDER' AND ref_id = ?::uuid",
                Integer.class, orderId);
    }

    private void expectOpenAndUnpaid(String orderId) throws Exception {
        mockMvc.perform(authed(get("/api/v1/orders/" + orderId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.payments.length()").value(0));
    }

    @Test
    void saleNeedingMoreThanOnHand_isRefusedAndNothingMoves() throws Exception {
        String name = "Croissant-" + uniq();
        String croissant = ingredient(name, "pc");
        adjust(croissant, "51");
        String order = order(product(uses(croissant, "1", "pc")), 101);
        String orderId = JsonPath.read(order, "$.id");

        String refusal = pay(order)
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_STOCK"))
                .andExpect(jsonPath("$.detail").value("Not enough stock: %s needs 101 pc, 51 left".formatted(name)))
                .andExpect(jsonPath("$.shortfalls.length()").value(1))
                .andExpect(jsonPath("$.shortfalls[0].ingredientId").value(croissant))
                .andExpect(jsonPath("$.shortfalls[0].ingredientName").value(name))
                .andExpect(jsonPath("$.shortfalls[0].unit").value("pc"))
                .andReturn().getResponse().getContentAsString();
        assertThat(num(refusal, "$.shortfalls[0].required")).isEqualByComparingTo("101");
        assertThat(num(refusal, "$.shortfalls[0].onHand")).isEqualByComparingTo("51");

        expectOpenAndUnpaid(orderId);
        assertThat(onHand(croissant)).isEqualByComparingTo("51");
        assertThat(depletions(orderId)).isZero();
        assertThat(ledgerClaims(orderId)).isZero();
    }

    @Test
    void refusedSale_succeedsWhenPaidAgainAfterRestocking() throws Exception {
        String croissant = ingredient("Croissant-" + uniq(), "pc");
        adjust(croissant, "1");
        String order = order(product(uses(croissant, "1", "pc")), 2);
        String orderId = JsonPath.read(order, "$.id");
        pay(order).andExpect(status().is(422)).andExpect(jsonPath("$.code").value("INSUFFICIENT_STOCK"));

        adjust(croissant, "4");
        pay(order).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("COMPLETED"));

        assertThat(onHand(croissant)).isEqualByComparingTo("3"); // 1 + 4 - 2
        assertThat(depletions(orderId)).isEqualTo(1);
        assertThat(ledgerClaims(orderId)).isEqualTo(1);
    }

    @Test
    void saleNeedingExactlyWhatIsOnHand_completesAndLeavesZero() throws Exception {
        String beans = ingredient("Beans-" + uniq(), "g");
        adjust(beans, "36");
        String order = order(product(uses(beans, "18", "g")), 2);

        pay(order).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("COMPLETED"));

        assertThat(onHand(beans)).isEqualByComparingTo("0");
    }

    @Test
    void ingredientTheBranchNeverStocked_countsAsZeroAndBlocks() throws Exception {
        String name = "Syrup-" + uniq();
        String syrup = ingredient(name, "ml");
        String order = order(product(uses(syrup, "15", "ml")), 2);
        String orderId = JsonPath.read(order, "$.id");

        String refusal = pay(order)
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_STOCK"))
                .andExpect(jsonPath("$.detail").value("Not enough stock: %s needs 30 ml, 0 left".formatted(name)))
                .andExpect(jsonPath("$.shortfalls[0].ingredientName").value(name))
                .andExpect(jsonPath("$.shortfalls[0].unit").value("ml"))
                .andReturn().getResponse().getContentAsString();
        assertThat(num(refusal, "$.shortfalls[0].required")).isEqualByComparingTo("30");
        // ledger scale: four decimal places, as every stored quantity
        assertThat(refusal).contains("\"onHand\":0.0000");

        expectOpenAndUnpaid(orderId);
        // the stock row the check created to lock rolled back with the refusal
        assertThat(onHand(syrup)).isNull();
        assertThat(ledgerClaims(orderId)).isZero();
    }

    @Test
    void productWithoutARecipe_sellsWithNoStockAtAll() throws Exception {
        String order = order(product(), 3);
        String orderId = JsonPath.read(order, "$.id");

        pay(order).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("COMPLETED"));

        assertThat(depletions(orderId)).isZero();
    }

    @Test
    void saleShortOnTwoIngredients_listsBothAndMovesNeither() throws Exception {
        String s = uniq();
        String beans = ingredient("Beans-" + s, "g");
        String milk = ingredient("Milk-" + s, "ml");
        String cups = ingredient("Cup-" + s, "pc");
        adjust(beans, "10");
        adjust(cups, "5");
        String order = order(product(uses(beans, "18", "g"), uses(milk, "200", "ml"), uses(cups, "1", "pc")), 1);
        String orderId = JsonPath.read(order, "$.id");

        String refusal = pay(order)
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_STOCK"))
                .andReturn().getResponse().getContentAsString();

        assertThat((List<String>) JsonPath.read(refusal, "$.shortfalls[*].ingredientName"))
                .containsExactlyInAnyOrder("Beans-" + s, "Milk-" + s);
        assertThat((String) JsonPath.read(refusal, "$.detail"))
                .startsWith("Not enough stock: ")
                .contains("Beans-%s needs 18 g, 10 left".formatted(s))
                .contains("Milk-%s needs 200 ml, 0 left".formatted(s))
                .doesNotContain("Cup-" + s);
        expectOpenAndUnpaid(orderId);
        assertThat(onHand(beans)).isEqualByComparingTo("10");
        assertThat(onHand(cups)).isEqualByComparingTo("5");
        assertThat(depletions(orderId)).isZero();
    }

    @Test
    void twoTillsPayingForTheLastUnitAtOnce_completeExactlyOneSale() throws Exception {
        String beans = ingredient("Beans-" + uniq(), "g");
        adjust(beans, "18");
        String product = product(uses(beans, "18", "g"));
        List<String> orders = List.of(order(product, 1), order(product, 1));

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Future<MvcResult>> results = new ArrayList<>();
        for (String order : orders) {
            results.add(pool.submit(() -> {
                start.await();
                return pay(order).andReturn();
            }));
        }
        start.countDown();
        List<MvcResult> responses = new ArrayList<>();
        try {
            for (Future<MvcResult> f : results) {
                responses.add(f.get(30, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(responses).extracting(r -> r.getResponse().getStatus()).containsExactlyInAnyOrder(200, 422);
        for (int i = 0; i < 2; i++) {
            String body = responses.get(i).getResponse().getContentAsString();
            String orderId = JsonPath.read(orders.get(i), "$.id");
            if (responses.get(i).getResponse().getStatus() == 200) {
                assertThat((String) JsonPath.read(body, "$.status")).isEqualTo("COMPLETED");
                assertThat(depletions(orderId)).isEqualTo(1);
            } else {
                assertThat((String) JsonPath.read(body, "$.code")).isEqualTo("INSUFFICIENT_STOCK");
                expectOpenAndUnpaid(orderId);
                assertThat(depletions(orderId)).isZero();
            }
        }
        assertThat(onHand(beans)).isEqualByComparingTo("0");
    }

    @Test
    void zeroTotalTicketThatIsShort_isRefusedAtCreateAndNoOrderRemains() throws Exception {
        String croissant = ingredient("Croissant-" + uniq(), "pc");
        adjust(croissant, "1");
        String product = product(uses(croissant, "1", "pc"));
        UUID requestId = UUID.randomUUID();

        // fully discounted: nothing to collect, so the ticket completes, and takes its stock, as it is created
        mockMvc.perform(authed(post("/api/v1/orders")).contentType(MediaType.APPLICATION_JSON)
                        .content(("{\"orderType\":\"TAKEAWAY\",\"orderDiscount\":20000,\"clientRequestId\":\"%s\","
                                + "\"lines\":[{\"productId\":\"%s\",\"quantity\":2}]}").formatted(requestId, product)))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_STOCK"))
                .andExpect(jsonPath("$.shortfalls[0].ingredientId").value(croissant));

        assertThat(db.queryForObject("SELECT count(*) FROM sales_order WHERE client_request_id = ?::uuid",
                Integer.class, requestId.toString())).isZero();
        assertThat(onHand(croissant)).isEqualByComparingTo("1");
    }

    @Test
    void lineThatGivesStockBack_isNeverRefused_evenBelowZero() throws Exception {
        String beans = ingredient("Beans-" + uniq(), "g");
        adjust(beans, "-50"); // a deficit left from before the guard, or a count correction

        // a modifier can make a line's net consumption negative: the sale returns 10 g
        TenantContext.runAs(companyId, () -> ledger.deduct(UUID.fromString(branchId),
                List.of(new StockLine(UUID.fromString(beans), new BigDecimal("-10"), "g")), "ORDER", UUID.randomUUID()));

        assertThat(onHand(beans)).isEqualByComparingTo("-40");
    }
}
