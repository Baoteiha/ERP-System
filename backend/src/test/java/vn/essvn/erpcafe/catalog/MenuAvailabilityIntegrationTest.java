package vn.essvn.erpcafe.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import javax.sql.DataSource;

import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.jayway.jsonpath.JsonPath;

import vn.essvn.erpcafe.support.AbstractIntegrationTest;

/**
 * Class: MenuAvailabilityIntegrationTest
 * Purpose: Proves the menu marks a product unavailable exactly when the branch cannot make one of it, from one stock read.
 */
@TestPropertySource(properties = "spring.jpa.properties.hibernate.session_factory.statement_inspector="
        + "vn.essvn.erpcafe.catalog.MenuAvailabilityIntegrationTest$TableReads")
class MenuAvailabilityIntegrationTest extends AbstractIntegrationTest {

    /**
     * Class: TableReads
     * Purpose: Counts every SQL statement Hibernate sends that names the stock or recipe tables, by whatever route it was built.
     */
    public static class TableReads implements StatementInspector {

        static final AtomicInteger STOCK = new AtomicInteger();
        static final AtomicInteger RECIPE = new AtomicInteger();
        private static final Pattern STOCK_TABLE = Pattern.compile("(?i)\\bstock_item\\b");
        private static final Pattern RECIPE_TABLES = Pattern.compile("(?i)\\brecipe(_version|_line)?\\b");

        static void reset() {
            STOCK.set(0);
            RECIPE.set(0);
        }

        @Override
        public String inspect(String sql) {
            if (STOCK_TABLE.matcher(sql).find()) {
                STOCK.incrementAndGet();
            }
            if (RECIPE_TABLES.matcher(sql).find()) {
                RECIPE.incrementAndGet();
            }
            return sql;
        }
    }

    /** The schema owner's SQL: looks for stock rows past row-level security. */
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

    private static String uses(String ingredientId, String quantity, String unit) {
        return "{\"ingredientId\":\"%s\",\"quantity\":%s,\"unit\":\"%s\"}".formatted(ingredientId, quantity, unit);
    }

    /** A 10,000 product using the given recipe lines; no lines means no recipe at all. Returns its id. */
    private String product(String... recipeLines) throws Exception {
        String s = uniq();
        String categoryId = JsonPath.read(create("/api/v1/categories",
                "{\"name\":\"Avail-%s\",\"displayOrder\":1}".formatted(s)), "$.id");
        String productId = JsonPath.read(create("/api/v1/products",
                "{\"categoryId\":\"%s\",\"name\":\"Avail-%s\",\"sku\":\"AVL-%s\",\"basePrice\":10000}"
                        .formatted(categoryId, s, s)), "$.id");
        if (recipeLines.length > 0) {
            mockMvc.perform(authed(put("/api/v1/products/" + productId + "/recipe"))
                            .content("{\"lines\":[%s]}".formatted(String.join(",", recipeLines))))
                    .andExpect(status().isOk());
        }
        return productId;
    }

    private String menu() throws Exception {
        return mockMvc.perform(authed(get("/api/v1/menu"))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private static boolean available(String menu, String productId) {
        List<Boolean> flags = JsonPath.read(menu, "$[?(@.id=='%s')].available".formatted(productId));
        assertThat(flags).as("menu entry for " + productId).hasSize(1);
        return flags.get(0);
    }

    /** Sells one of the product and pays it in full, which completes the sale and takes its stock. */
    private void sellOne(String productId) throws Exception {
        String order = create("/api/v1/orders",
                "{\"orderType\":\"TAKEAWAY\",\"lines\":[{\"productId\":\"%s\",\"quantity\":1}]}".formatted(productId));
        BigDecimal total = new BigDecimal(String.valueOf((Object) JsonPath.read(order, "$.grandTotal")));
        mockMvc.perform(authed(post("/api/v1/orders/" + JsonPath.read(order, "$.id") + "/payments"))
                        .content("{\"method\":\"CASH\",\"amount\":%s}".formatted(total.toPlainString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    @Test
    void productIsUnavailable_exactlyWhenOneOfItExceedsStock() throws Exception {
        String s = uniq();
        String beans = ingredient("Beans-" + s, "g");
        String syrup = ingredient("Syrup-" + s, "ml");
        adjust(beans, "36");
        String espresso = product(uses(beans, "18", "g"));
        String doppio = product(uses(beans, "36", "g"));       // exactly what is on hand
        String bigCup = product(uses(beans, "0.037", "kg"));   // 37 g: one gram over, in another unit
        String syrupShot = product(uses(syrup, "15", "ml"));   // never stocked at the branch
        String mocha = product(uses(beans, "18", "g"), uses(syrup, "15", "ml"));
        String water = product();                              // no recipe

        String menu = menu();

        assertThat(available(menu, espresso)).isTrue();
        assertThat(available(menu, doppio)).isTrue();
        assertThat(available(menu, bigCup)).isFalse();
        assertThat(available(menu, syrupShot)).isFalse();
        assertThat(available(menu, mocha)).isFalse();
        assertThat(available(menu, water)).isTrue();
        // read-only: judging the never-stocked syrup created no stock row for it
        assertThat(db.queryForObject("SELECT count(*) FROM stock_item WHERE ingredient_id = ?::uuid",
                Integer.class, syrup)).isZero();
    }

    @Test
    void productsSharingAnIngredient_areEachCheckedOnTheirOwn() throws Exception {
        String beans = ingredient("Beans-" + uniq(), "g");
        adjust(beans, "18");
        String first = product(uses(beans, "18", "g"));
        String second = product(uses(beans, "18", "g"));

        String menu = menu();

        // one of each fits; the menu does not add the two together
        assertThat(available(menu, first)).isTrue();
        assertThat(available(menu, second)).isTrue();
    }

    @Test
    void sellingDownToZero_flipsTheProductToUnavailable() throws Exception {
        String beans = ingredient("Beans-" + uniq(), "g");
        adjust(beans, "18");
        String espresso = product(uses(beans, "18", "g"));
        assertThat(available(menu(), espresso)).isTrue();

        sellOne(espresso);

        assertThat(available(menu(), espresso)).isFalse();
    }

    @Test
    void theWholeMenu_isCheckedWithOneStockReadAndOneRecipeRead() throws Exception {
        String s = uniq();
        String beans = ingredient("Beans-" + s, "g");
        String milk = ingredient("Milk-" + s, "ml");
        adjust(beans, "100");
        for (int i = 0; i < 5; i++) {
            product(uses(beans, "18", "g"), uses(milk, "100", "ml"));
        }
        TableReads.reset();

        menu();

        // counted at the JDBC statement, so JPQL, derived, entity, lazy and native reads all count alike
        assertThat(TableReads.STOCK.get()).as("statements reading stock_item").isEqualTo(1);
        assertThat(TableReads.RECIPE.get()).as("statements reading the recipe tables").isEqualTo(1);
    }
}
