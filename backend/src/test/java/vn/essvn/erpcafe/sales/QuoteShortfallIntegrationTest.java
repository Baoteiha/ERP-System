package vn.essvn.erpcafe.sales;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

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
 * Class: QuoteShortfallIntegrationTest
 * Purpose: Proves the price check lists every stock shortfall of the cart, modifiers included, without refusing or saving anything.
 */
class QuoteShortfallIntegrationTest extends AbstractIntegrationTest {

    /** The schema owner's SQL: counts orders and movements past row-level security. */
    private JdbcTemplate db;

    private String token;
    private String branchId;

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
    }

    /** Globally-unique suffix — all integration tests share one Testcontainers DB, so names must not collide. */
    private static String uniq() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder b) {
        return b.header(HttpHeaders.AUTHORIZATION, authHeader(token)).header("X-Branch-Id", branchId)
                .contentType(MediaType.APPLICATION_JSON);
    }

    private String create(String url, String body) throws Exception {
        return mockMvc.perform(authed(post(url)).content(body))
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

    /** One recipe line or modifier delta as the catalog endpoints take it. */
    private static String uses(String ingredientId, String quantity, String unit) {
        return "{\"ingredientId\":\"%s\",\"quantity\":%s,\"unit\":\"%s\"}".formatted(ingredientId, quantity, unit);
    }

    /** A 10,000 product using the given recipe lines; no lines means no recipe at all. Returns its id. */
    private String product(String... recipeLines) throws Exception {
        String s = uniq();
        String categoryId = JsonPath.read(create("/api/v1/categories",
                "{\"name\":\"Quote-%s\",\"displayOrder\":1}".formatted(s)), "$.id");
        String productId = JsonPath.read(create("/api/v1/products",
                "{\"categoryId\":\"%s\",\"name\":\"Quoted-%s\",\"sku\":\"QSF-%s\",\"basePrice\":10000}"
                        .formatted(categoryId, s, s)), "$.id");
        if (recipeLines.length > 0) {
            mockMvc.perform(authed(put("/api/v1/products/" + productId + "/recipe"))
                            .content("{\"lines\":[%s]}".formatted(String.join(",", recipeLines))))
                    .andExpect(status().isOk());
        }
        return productId;
    }

    /** Offers the product a modifier with the given ingredient deltas; returns the modifier id. */
    private String modifier(String productId, String... deltas) throws Exception {
        String s = uniq();
        String groupId = JsonPath.read(create("/api/v1/modifier-groups",
                "{\"name\":\"Size-%s\",\"minSelect\":0,\"maxSelect\":1}".formatted(s)), "$.id");
        String modifierId = JsonPath.read(create("/api/v1/modifier-groups/" + groupId + "/modifiers",
                "{\"name\":\"L-%s\",\"priceDelta\":0,\"displayOrder\":1,\"recipeDeltas\":[%s]}"
                        .formatted(s, String.join(",", deltas))), "$.id");
        mockMvc.perform(authed(put("/api/v1/products/" + productId + "/modifier-groups"))
                        .content("{\"modifierGroupIds\":[\"%s\"]}".formatted(groupId)))
                .andExpect(status().isOk());
        return modifierId;
    }

    private static String cart(String productId, int quantity, String... modifierIds) {
        String mods = modifierIds.length == 0 ? ""
                : ",\"modifierIds\":[\"%s\"]".formatted(String.join("\",\"", modifierIds));
        return "{\"orderType\":\"TAKEAWAY\",\"lines\":[{\"productId\":\"%s\",\"quantity\":%d%s}]}"
                .formatted(productId, quantity, mods);
    }

    private ResultActions quote(String body) throws Exception {
        return mockMvc.perform(authed(post("/api/v1/orders/quote")).content(body));
    }

    /** Reads a numeric JSON field as BigDecimal; the (Object) cast picks String.valueOf(Object). */
    private static BigDecimal num(String json, String path) {
        return new BigDecimal(String.valueOf((Object) JsonPath.read(json, path)));
    }

    private int orderCount() {
        return db.queryForObject("SELECT count(*) FROM sales_order", Integer.class);
    }

    private int movements(String ingredientId) {
        return db.queryForObject("SELECT count(*) FROM stock_movement WHERE ingredient_id = ?::uuid",
                Integer.class, ingredientId);
    }

    @Test
    void cartWithinStock_quotesNoShortfalls() throws Exception {
        String beans = ingredient("Beans-" + uniq(), "g");
        adjust(beans, "36");

        quote(cart(product(uses(beans, "18", "g")), 2))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stockShortfalls").isArray())
                .andExpect(jsonPath("$.stockShortfalls.length()").value(0));
    }

    @Test
    void cartOverStock_listsTheShortfallWithItsFigures_andStillQuotesAndSavesNothing() throws Exception {
        String name = "Croissant-" + uniq();
        String croissant = ingredient(name, "pc");
        adjust(croissant, "51");
        String product = product(uses(croissant, "1", "pc"));
        int ordersBefore = orderCount();
        int movementsBefore = movements(croissant);

        String json = quote(cart(product, 101))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.grandTotal").value(1010000))
                .andExpect(jsonPath("$.stockShortfalls.length()").value(1))
                .andExpect(jsonPath("$.stockShortfalls[0].ingredientId").value(croissant))
                .andExpect(jsonPath("$.stockShortfalls[0].ingredientName").value(name))
                .andExpect(jsonPath("$.stockShortfalls[0].unit").value("pc"))
                .andReturn().getResponse().getContentAsString();
        assertThat(num(json, "$.stockShortfalls[0].required")).isEqualByComparingTo("101");
        assertThat(num(json, "$.stockShortfalls[0].onHand")).isEqualByComparingTo("51");

        assertThat(orderCount()).isEqualTo(ordersBefore);
        assertThat(movements(croissant)).isEqualTo(movementsBefore);
    }

    @Test
    void untrackedIngredient_isShortWithNothingOnHand_andLeavesNoStockRow() throws Exception {
        String syrup = ingredient("Syrup-" + uniq(), "ml");

        String json = quote(cart(product(uses(syrup, "15", "ml")), 2))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stockShortfalls[0].ingredientId").value(syrup))
                .andReturn().getResponse().getContentAsString();
        assertThat(num(json, "$.stockShortfalls[0].required")).isEqualByComparingTo("30");
        assertThat(num(json, "$.stockShortfalls[0].onHand")).isEqualByComparingTo("0");

        // read-only: the check neither locks nor creates the row the payment would
        assertThat(db.queryForObject("SELECT count(*) FROM stock_item WHERE ingredient_id = ?::uuid",
                Integer.class, syrup)).isZero();
    }

    @Test
    void modifierThatAddsAnOutOfStockIngredient_isListed_thoughTheBareProductFits() throws Exception {
        String s = uniq();
        String beans = ingredient("Beans-" + s, "g");
        String milkName = "Milk-" + s;
        String milk = ingredient(milkName, "ml");
        adjust(beans, "100");
        adjust(milk, "30");
        String latte = product(uses(beans, "18", "g"));
        String sizeL = modifier(latte, uses(milk, "50", "ml"), uses(beans, "2", "g"));

        quote(cart(latte, 1)).andExpect(status().isOk()).andExpect(jsonPath("$.stockShortfalls.length()").value(0));
        // the tile knows only the bare product, which fits: the order panel is what catches the modifier
        mockMvc.perform(authed(get("/api/v1/menu"))).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id=='%s')].available".formatted(latte)).value(true));

        String json = quote(cart(latte, 1, sizeL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stockShortfalls.length()").value(1))
                .andExpect(jsonPath("$.stockShortfalls[0].ingredientName").value(milkName))
                .andExpect(jsonPath("$.stockShortfalls[0].unit").value("ml"))
                .andReturn().getResponse().getContentAsString();
        assertThat(num(json, "$.stockShortfalls[0].required")).isEqualByComparingTo("50");
        assertThat(num(json, "$.stockShortfalls[0].onHand")).isEqualByComparingTo("30");
    }

    @Test
    void modifierThatNetsAnIngredientToZero_isNotShort_andThePaymentAgrees() throws Exception {
        String beans = ingredient("Beans-" + uniq(), "g");
        adjust(beans, "-50"); // a deficit from before the guard, or a count correction
        String product = product(uses(beans, "10", "g"));
        String noBeans = modifier(product, uses(beans, "-0.01", "kg")); // cancels the 10 g across units

        quote(cart(product, 2, noBeans))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stockShortfalls.length()").value(0));

        String order = create("/api/v1/orders", cart(product, 2, noBeans));
        mockMvc.perform(authed(post("/api/v1/orders/" + JsonPath.read(order, "$.id") + "/payments"))
                        .content("{\"method\":\"CASH\",\"amount\":%s}".formatted(num(order, "$.grandTotal").toPlainString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    @Test
    void createResponse_hasNoStockShortfallsKey() throws Exception {
        String syrup = ingredient("Syrup-" + uniq(), "ml");

        mockMvc.perform(authed(post("/api/v1/orders")).content(cart(product(uses(syrup, "15", "ml")), 1)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.stockShortfalls").doesNotExist());
    }
}
