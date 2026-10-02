package vn.essvn.erpcafe.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
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
import com.zaxxer.hikari.HikariDataSource;

import vn.essvn.erpcafe.catalog.application.ConsumptionInput;
import vn.essvn.erpcafe.catalog.application.RecipeService;
import vn.essvn.erpcafe.catalog.domain.Category;
import vn.essvn.erpcafe.catalog.domain.Product;
import vn.essvn.erpcafe.catalog.persistence.CategoryRepository;
import vn.essvn.erpcafe.catalog.persistence.ProductRepository;
import vn.essvn.erpcafe.common.context.TenantContext;
import vn.essvn.erpcafe.common.domain.Money;
import vn.essvn.erpcafe.common.exception.ResourceNotFoundException;
import vn.essvn.erpcafe.inventory.api.ConsumptionResolver;
import vn.essvn.erpcafe.inventory.api.StockLine;
import vn.essvn.erpcafe.inventory.domain.Ingredient;
import vn.essvn.erpcafe.inventory.persistence.IngredientRepository;
import vn.essvn.erpcafe.organization.domain.Company;
import vn.essvn.erpcafe.organization.persistence.CompanyRepository;
import vn.essvn.erpcafe.support.AbstractIntegrationTest;

/**
 * Class: RecipeVersioningTest
 * Purpose: Proves a sale consumes the recipe version in force when its line was taken, and that every recipe save is a new, immutable version.
 */
class RecipeVersioningTest extends AbstractIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired
    @Qualifier("ownerDataSource")
    private DataSource ownerDataSource;

    @Autowired
    private JdbcTemplate appJdbc;

    @Autowired private ConsumptionResolver resolver;
    @Autowired private RecipeService recipeService;
    @Autowired private CompanyRepository companyRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private IngredientRepository ingredientRepository;

    private String token;
    private String branchId;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void signIn() throws Exception {
        token = adminToken();
        branchId = ((List<String>) JsonPath.read(mockMvc.perform(get("/api/v1/auth/me")
                        .header(HttpHeaders.AUTHORIZATION, authHeader(token)))
                .andReturn().getResponse().getContentAsString(), "$.branchIds")).get(0);
    }

    private JdbcTemplate owner() {
        return new JdbcTemplate(ownerDataSource);
    }

    private MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder b) {
        return b.header(HttpHeaders.AUTHORIZATION, authHeader(token)).header("X-Branch-Id", branchId)
                .contentType(MediaType.APPLICATION_JSON);
    }

    private String created(String url, String body) throws Exception {
        return JsonPath.read(mockMvc.perform(authed(post(url)).content(body))
                .andExpect(status().is2xxSuccessful()).andReturn().getResponse().getContentAsString(), "$.id");
    }

    private String ingredient() throws Exception {
        return created("/api/v1/ingredients", "{\"name\":\"RVer%d\",\"baseUnit\":\"g\"}".formatted(SEQ.incrementAndGet()));
    }

    /** A 25,000 product with no recipe yet. */
    private String product() throws Exception {
        int n = SEQ.incrementAndGet();
        String categoryId = created("/api/v1/categories", "{\"name\":\"RVer%d\",\"displayOrder\":1}".formatted(n));
        return created("/api/v1/products", "{\"categoryId\":\"%s\",\"name\":\"RVer%d\",\"sku\":\"RVER%d\",\"basePrice\":25000}"
                .formatted(categoryId, n, n));
    }

    private ResultActions saveRecipe(String productId, String ingredientId, String grams, String unit) throws Exception {
        return mockMvc.perform(authed(put("/api/v1/products/" + productId + "/recipe"))
                .content("{\"lines\":[{\"ingredientId\":\"%s\",\"quantity\":%s,\"unit\":\"%s\"}]}"
                        .formatted(ingredientId, grams, unit)));
    }

    /** An order for one of the product, left unpaid (the branch charges no tax: 25,000 is due). */
    private String openOrder(String productId) throws Exception {
        return created("/api/v1/orders",
                "{\"orderType\":\"TAKEAWAY\",\"lines\":[{\"productId\":\"%s\",\"quantity\":1}]}".formatted(productId));
    }

    private void payInFull(String orderId) throws Exception {
        mockMvc.perform(authed(post("/api/v1/orders/" + orderId + "/payments"))
                        .content("{\"method\":\"CASH\",\"amount\":25000}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    /** What the order's sale took from stock of the ingredient, read from the ledger (positive = consumed). */
    private BigDecimal consumed(String orderId, String ingredientId) {
        return owner().queryForObject("SELECT coalesce(-sum(quantity), 0) FROM stock_movement"
                + " WHERE ref_type = 'ORDER' AND ref_id = ?::uuid AND ingredient_id = ?::uuid",
                BigDecimal.class, orderId, ingredientId);
    }

    // --- a sale consumes the version its line was taken under ---

    @Test
    void anOrderTakenUnderVersionOne_andPaidAfterVersionTwo_consumesVersionOne() throws Exception {
        String beans = ingredient();
        String productId = product();
        saveRecipe(productId, beans, "18", "g").andExpect(status().isOk());
        String takenUnderV1 = openOrder(productId);

        saveRecipe(productId, beans, "20", "g").andExpect(status().isOk());
        String takenUnderV2 = openOrder(productId);
        payInFull(takenUnderV1);
        payInFull(takenUnderV2);

        assertThat(consumed(takenUnderV1, beans)).isEqualByComparingTo("18");
        assertThat(consumed(takenUnderV2, beans)).isEqualByComparingTo("20");
    }

    @Test
    void aLineTakenBeforeTheProductHadARecipe_consumesNothing_evenIfOneIsAddedBeforePayment() throws Exception {
        String beans = ingredient();
        String productId = product();
        String takenWithoutRecipe = openOrder(productId);

        saveRecipe(productId, beans, "18", "g").andExpect(status().isOk());
        payInFull(takenWithoutRecipe);

        assertThat(consumed(takenWithoutRecipe, beans)).isZero();
    }

    // --- every save is a new version ---

    @Test
    void savingARecipeTwice_producesVersionsOneAndTwo_andKeepsVersionOneAsItWas() throws Exception {
        String beans = ingredient();
        String productId = product();

        saveRecipe(productId, beans, "18", "g").andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1));
        mockMvc.perform(authed(put("/api/v1/products/" + productId + "/recipe"))
                        .content("{\"lines\":[{\"ingredientId\":\"%s\",\"quantity\":20,\"unit\":\"g\"}],\"version\":1}"
                                .formatted(beans)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.lines[0].quantity").value(20));

        List<Map<String, Object>> versions = owner().queryForList("SELECT v.version_no, l.quantity, l.unit"
                + " FROM recipe_version v JOIN recipe r ON r.id = v.recipe_id"
                + " JOIN recipe_line l ON l.recipe_version_id = v.id"
                + " WHERE r.product_id = ?::uuid ORDER BY v.version_no", productId);
        assertThat(versions).extracting(row -> row.get("version_no")).containsExactly(1, 2);
        assertThat(versions).extracting(row -> ((BigDecimal) row.get("quantity")).intValue()).containsExactly(18, 20);
        mockMvc.perform(authed(get("/api/v1/products/" + productId + "/recipe")))
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.lines[0].quantity").value(20));
    }

    @Test
    void twoSavesFromTheSameVersion_oneBecomesVersionTwo_theOtherIsAConflict() throws Exception {
        String beans = ingredient();
        String productId = product();
        saveRecipe(productId, beans, "18", "g").andExpect(status().isOk());
        String fromV1 = "{\"lines\":[{\"ingredientId\":\"%s\",\"quantity\":%d,\"unit\":\"g\"}],\"version\":1}";
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Future<Integer>> results = new ArrayList<>();
        for (int grams : List.of(19, 20)) {
            results.add(pool.submit(() -> {
                start.await();
                return mockMvc.perform(authed(put("/api/v1/products/" + productId + "/recipe"))
                        .content(fromV1.formatted(beans, grams))).andReturn().getResponse().getStatus();
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

        assertThat(codes).containsExactlyInAnyOrder(200, 409);
        assertThat(owner().queryForList("SELECT v.version_no FROM recipe_version v JOIN recipe r ON r.id = v.recipe_id"
                + " WHERE r.product_id = ?::uuid ORDER BY v.version_no", Integer.class, productId))
                .containsExactly(1, 2);
    }

    @Test
    void aRecipeLineIsStoredInTheCanonicalSpellingOfItsUnit() throws Exception {
        String productId = product();

        saveRecipe(productId, ingredient(), "0.018", "Kilograms").andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[0].unit").value("kg"));

        assertThat(owner().queryForObject("SELECT l.unit FROM recipe_line l"
                + " JOIN recipe_version v ON v.id = l.recipe_version_id JOIN recipe r ON r.id = v.recipe_id"
                + " WHERE r.product_id = ?::uuid", String.class, productId)).isEqualTo("kg");
    }

    @Test
    void aRecipeLineInCups_isRefused_andNoVersionIsSaved() throws Exception {
        String productId = product();

        saveRecipe(productId, ingredient(), "1", "cup")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("BUSINESS_RULE"));

        assertThat(owner().queryForObject("SELECT count(*) FROM recipe WHERE product_id = ?::uuid",
                Long.class, productId)).isZero();
    }

    @Test
    void theAppRole_cannotRewriteOrDeleteASavedVersion() {
        for (String table : List.of("recipe_version", "recipe_line")) {
            assertThat(appJdbc.queryForObject("SELECT has_table_privilege(?, 'SELECT, INSERT')", Boolean.class, table))
                    .as(table).isTrue();
            assertThat(appJdbc.queryForObject("SELECT has_table_privilege(?, 'UPDATE')", Boolean.class, table))
                    .as(table).isFalse();
            assertThat(appJdbc.queryForObject("SELECT has_table_privilege(?, 'DELETE')", Boolean.class, table))
                    .as(table).isFalse();
        }
    }

    // --- the port answers only for the company it names ---

    private record Foreign(UUID company, UUID product, UUID ingredient, UUID version) {
    }

    /** Another company with a product whose recipe takes 18 g of its own beans. */
    private Foreign seedForeign() {
        int n = SEQ.incrementAndGet();
        Company company = TenantContext.runAsSystem(
                () -> companyRepository.save(new Company("RVer Foreign %d".formatted(n), "RVERF%d".formatted(n))));
        return TenantContext.runAs(company.getId(), () -> {
            Category category = categoryRepository.save(new Category("RVer Foreign %d".formatted(n), 0));
            Product product = productRepository.save(new Product(category.getId(), "Latte", "RVERF%d".formatted(n),
                    Money.of(new BigDecimal("25000"))));
            Ingredient beans = ingredientRepository.save(new Ingredient("Beans %d".formatted(n), "g", null));
            UUID version = recipeService.setRecipe(product.getId(),
                    List.of(new ConsumptionInput(beans.getId(), new BigDecimal("18"), "g")), null).getId();
            return new Foreign(company.getId(), product.getId(), beans.getId(), version);
        });
    }

    @Test
    void theConsumptionPort_doesNotAnswerForAnotherCompanysProductOrVersion() {
        Foreign b = seedForeign();
        UUID a = seedForeign().company();

        TenantContext.runAs(a, () -> {
            assertThat(resolver.currentRecipeVersion(a, b.product())).isEmpty();
            assertThatThrownBy(() -> resolver.consumptionFor(a, b.product(), 1, Set.of(), b.version()))
                    .isInstanceOf(ResourceNotFoundException.class);
            // naming the other company from inside this one reaches nothing either
            assertThat(resolver.currentRecipeVersion(b.company(), b.product())).isEmpty();
            assertThatThrownBy(() -> resolver.consumptionFor(b.company(), b.product(), 1, Set.of(), b.version()))
                    .isInstanceOf(ResourceNotFoundException.class);
        });

        List<StockLine> own = TenantContext.runAs(b.company(),
                () -> resolver.consumptionFor(b.company(), b.product(), 2, Set.of(), b.version()));
        assertThat(own).singleElement().satisfies(line -> {
            assertThat(line.ingredientId()).isEqualTo(b.ingredient());
            assertThat(line.quantity()).isEqualByComparingTo("36");
            assertThat(line.unit()).isEqualTo("g");
        });
        assertThat(TenantContext.runAs(b.company(), () -> resolver.currentRecipeVersion(b.company(), b.product())))
                .contains(b.version());
    }

    // --- the migration over existing recipes and orders ---

    private Flyway flyway(String url, String target) {
        HikariDataSource superuser = (HikariDataSource) ownerDataSource;
        var config = Flyway.configure()
                .dataSource(url, superuser.getUsername(), superuser.getPassword())
                .locations("classpath:db/migration")
                .placeholders(Map.of("appRolePassword", "unused-the-role-exists", "reportingZone", "Asia/Ho_Chi_Minh"));
        return (target == null ? config : config.target(target)).load();
    }

    /** Runs work against a new database migrated up to V23 (the schema before versions), and drops it after. */
    private void inALegacyDatabase(LegacyWork work) throws Exception {
        String database = "recipe_legacy_" + SEQ.incrementAndGet();
        owner().execute("CREATE DATABASE " + database);
        try {
            String url = ((HikariDataSource) ownerDataSource).getJdbcUrl()
                    .replaceFirst("/[^/?]+(\\?|$)", "/" + database + "$1");
            Flyway legacy = flyway(url, "23.002");
            legacy.migrate();
            work.run(new JdbcTemplate(legacy.getConfiguration().getDataSource()), () -> flyway(url, null).migrate());
        } finally {
            owner().execute("DROP DATABASE IF EXISTS " + database + " WITH (FORCE)");
        }
    }

    private interface LegacyWork {
        void run(JdbcTemplate db, Runnable migrateToLatest) throws Exception;
    }

    private record Legacy(UUID recipe, Timestamp lastEdited) {
    }

    /** A company with one product whose recipe takes 18 of beans in the given unit, last edited a day ago. */
    private Legacy legacyRecipe(JdbcTemplate db, String unit) {
        UUID company = UUID.randomUUID();
        UUID branch = UUID.randomUUID();
        UUID category = UUID.randomUUID();
        UUID product = UUID.randomUUID();
        UUID ingredient = UUID.randomUUID();
        UUID recipe = UUID.randomUUID();
        Timestamp lastEdited = owner().queryForObject("SELECT date_trunc('second', now() - interval '1 day')",
                Timestamp.class);
        db.update("INSERT INTO company (id, version, name, code, created_at, updated_at)"
                + " VALUES (?, 0, 'Legacy', 'LEG', now(), now())", company);
        db.update("INSERT INTO branch (id, version, company_id, name, code, created_at, updated_at)"
                + " VALUES (?, 0, ?, 'Main', 'MAIN', now(), now())", branch, company);
        db.update("INSERT INTO category (id, version, company_id, name, created_at, updated_at)"
                + " VALUES (?, 0, ?, 'Drinks', now(), now())", category, company);
        db.update("INSERT INTO product (id, version, company_id, category_id, name, sku, created_at, updated_at)"
                + " VALUES (?, 0, ?, ?, 'Latte', 'LAT', now(), now())", product, company, category);
        db.update("INSERT INTO ingredient (id, version, company_id, name, base_unit, created_at, updated_at)"
                + " VALUES (?, 0, ?, 'Beans', 'g', now(), now())", ingredient, company);
        db.update("INSERT INTO recipe (id, version, product_id, created_at, updated_at)"
                + " VALUES (?, 3, ?, ?, ?)", recipe, product, lastEdited, lastEdited);
        db.update("INSERT INTO recipe_line (id, version, recipe_id, ingredient_id, quantity, unit)"
                + " VALUES (?, 0, ?, ?, 18, ?)", UUID.randomUUID(), recipe, ingredient, unit);
        for (String status : List.of("OPEN", "PAID", "COMPLETED", "CANCELLED")) {
            UUID order = UUID.randomUUID();
            db.update("INSERT INTO sales_order (id, version, branch_id, order_type, status, currency, created_at,"
                    + " updated_at) VALUES (?, 0, ?, 'DINE_IN', ?, 'VND', now(), now())", order, branch, status);
            db.update("INSERT INTO order_line (id, version, order_id, product_id, product_name, quantity, line_total)"
                    + " VALUES (?, 0, ?, ?, 'Latte', 1, 25000)", UUID.randomUUID(), order, product);
        }
        return new Legacy(recipe, lastEdited);
    }

    @Test
    void migration_makesEachRecipeItsVersionOne_andPinsTheOrdersThatCanStillComplete() throws Exception {
        inALegacyDatabase((db, migrateToLatest) -> {
            Legacy legacy = legacyRecipe(db, "Gram");

            migrateToLatest.run();

            Map<String, Object> v1 = db.queryForMap("SELECT id, version_no, created_at FROM recipe_version"
                    + " WHERE recipe_id = ?", legacy.recipe());
            assertThat(v1).containsEntry("version_no", 1).containsEntry("created_at", legacy.lastEdited());
            assertThat(db.queryForObject("SELECT current_version_no FROM recipe WHERE id = ?", Integer.class,
                    legacy.recipe())).isEqualTo(1);
            assertThat(db.queryForMap("SELECT recipe_version_id, quantity, unit FROM recipe_line"))
                    .containsEntry("recipe_version_id", v1.get("id")).containsEntry("unit", "g");
            Map<String, Object> pinned = new HashMap<>();
            db.queryForList("SELECT o.status, l.recipe_version_id FROM order_line l"
                    + " JOIN sales_order o ON o.id = l.order_id").forEach(row ->
                    pinned.put((String) row.get("status"), row.get("recipe_version_id")));
            // an open or paid order still deducts at completion: under the only version there is
            assertThat(pinned).containsEntry("OPEN", v1.get("id")).containsEntry("PAID", v1.get("id"));
            // a finished order has taken its stock already (or never will): the ledger is its record
            assertThat(pinned).containsEntry("COMPLETED", null).containsEntry("CANCELLED", null);
        });
    }

    @Test
    void migration_stopsOnARecipeLineInAUnitNobodyCanConvert() throws Exception {
        inALegacyDatabase((db, migrateToLatest) -> {
            legacyRecipe(db, "cup");

            Throwable refused = catchThrowable(migrateToLatest::run);

            assertThat(refused).hasStackTraceContaining("recipe_line row(s) have a unit outside");
        });
    }
}
