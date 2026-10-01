package vn.essvn.erpcafe.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.jayway.jsonpath.JsonPath;

import vn.essvn.erpcafe.identity.persistence.UserBranchAccessRepository;
import vn.essvn.erpcafe.organization.persistence.BranchRepository;
import vn.essvn.erpcafe.sales.domain.Order;
import vn.essvn.erpcafe.sales.domain.OrderType;
import vn.essvn.erpcafe.sales.persistence.OrderRepository;
import vn.essvn.erpcafe.support.AbstractIntegrationTest;

/**
 * Class: SchemaHygieneIntegrationTest
 * Purpose: Proves deleted names and codes can be reused, purchase orders list newest first, sales rows obey their constraints and V21 applies over legacy rows.
 */
class SchemaHygieneIntegrationTest extends AbstractIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger();
    /** A scratch schema in the test database, so the app's own schema is never migrated backwards. */
    private static final String SCHEMA = "legacy_schema_hygiene";

    @Autowired
    private DataSource dataSource;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private BranchRepository branches;
    @Autowired
    private UserBranchAccessRepository grants;
    @Autowired
    private OrderRepository orders;

    private String token;
    private String branchId;
    private final List<UUID> createdBranches = new ArrayList<>();
    private final List<Order> createdOrders = new ArrayList<>();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        token = adminToken();
        branchId = ((List<String>) JsonPath.read(
                mockMvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, authHeader(token)))
                        .andReturn().getResponse().getContentAsString(),
                "$.branchIds")).get(0);
    }

    @BeforeEach
    void dropLeftoverSchema() {
        jdbc.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
    }

    @AfterEach
    void cleanUp() {
        orders.deleteAll(createdOrders);
        for (UUID id : createdBranches) {
            grants.deleteAll(grants.findByBranchId(id));
            branches.findById(id).ifPresent(branches::delete);
        }
        jdbc.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
    }

    private MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder b) {
        return b.header(HttpHeaders.AUTHORIZATION, authHeader(token)).header("X-Branch-Id", branchId);
    }

    private String createOk(String url, String body) throws Exception {
        return mockMvc.perform(authed(post(url)).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
    }

    private void deleteOk(String url, String id) throws Exception {
        mockMvc.perform(authed(delete(url + "/" + id))).andExpect(status().isNoContent());
    }

    /** Creates, deletes, then creates again with the same body; the second create must succeed. */
    private void recreatable(String url, String body) throws Exception {
        String id = JsonPath.read(createOk(url, body), "$.id");
        deleteOk(url, id);
        String again = JsonPath.read(createOk(url, body), "$.id");
        assertThat(again).isNotEqualTo(id);
        deleteOk(url, again);
    }

    @Test
    void deletedSku_canBeUsedAgain() throws Exception {
        int n = SEQ.incrementAndGet();
        String categoryId = JsonPath.read(createOk("/api/v1/categories",
                "{\"name\":\"Hyg Cat %d\",\"displayOrder\":1}".formatted(n)), "$.id");
        recreatable("/api/v1/products", ("{\"categoryId\":\"%s\",\"name\":\"Latte %d\",\"sku\":\"LAT1-%d\","
                + "\"basePrice\":45000}").formatted(categoryId, n, n));
        deleteOk("/api/v1/categories", categoryId);
    }

    @Test
    void deletedCategoryName_canBeUsedAgain() throws Exception {
        recreatable("/api/v1/categories", "{\"name\":\"Hyg Cat %d\",\"displayOrder\":1}".formatted(SEQ.incrementAndGet()));
    }

    @Test
    void deletedIngredientName_canBeUsedAgain() throws Exception {
        recreatable("/api/v1/ingredients", "{\"name\":\"Hyg Bean %d\",\"baseUnit\":\"g\"}".formatted(SEQ.incrementAndGet()));
    }

    @Test
    void deletedSupplierName_canBeUsedAgain() throws Exception {
        recreatable("/api/v1/suppliers", "{\"name\":\"Hyg Roaster %d\"}".formatted(SEQ.incrementAndGet()));
    }

    @Test
    void deletedBranchCode_canBeUsedAgain() throws Exception {
        int n = SEQ.incrementAndGet();
        String body = "{\"name\":\"Hyg Branch %d\",\"code\":\"HY%d\"}".formatted(n, n);
        String first = JsonPath.read(createOk("/api/v1/branches", body), "$.id");
        createdBranches.add(UUID.fromString(first));
        deleteOk("/api/v1/branches", first);
        String second = JsonPath.read(createOk("/api/v1/branches", body), "$.id");
        createdBranches.add(UUID.fromString(second));
        assertThat(second).isNotEqualTo(first);
    }

    @Test
    void liveDuplicates_stillConflict() throws Exception {
        String body = "{\"name\":\"Hyg Dup %d\"}".formatted(SEQ.incrementAndGet());
        String id = JsonPath.read(createOk("/api/v1/suppliers", body), "$.id");
        mockMvc.perform(authed(post("/api/v1/suppliers")).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict());
        deleteOk("/api/v1/suppliers", id);
    }

    @Test
    void purchaseOrderList_defaultsToNewestFirst() throws Exception {
        int n = SEQ.incrementAndGet();
        String ingredientId = JsonPath.read(createOk("/api/v1/ingredients",
                "{\"name\":\"Hyg PO Bean %d\",\"baseUnit\":\"g\"}".formatted(n)), "$.id");
        String supplierId = JsonPath.read(createOk("/api/v1/suppliers", "{\"name\":\"Hyg PO Sup %d\"}".formatted(n)), "$.id");
        String lines = "\"lines\":[{\"ingredientId\":\"%s\",\"orderedQty\":10,\"unitCost\":1}]".formatted(ingredientId);
        String older = JsonPath.read(createOk("/api/v1/purchase-orders",
                "{\"supplierId\":\"%s\",%s}".formatted(supplierId, lines)), "$.id");
        String newer = JsonPath.read(createOk("/api/v1/purchase-orders",
                "{\"supplierId\":\"%s\",%s}".formatted(supplierId, lines)), "$.id");

        String page = mockMvc.perform(authed(get("/api/v1/purchase-orders")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        List<String> ids = JsonPath.read(page, "$.content[*].id");
        assertThat(ids.indexOf(newer)).isGreaterThanOrEqualTo(0).isLessThan(ids.indexOf(older));
    }

    @Test
    void paymentOfZero_failsTheCheck() {
        Order order = orders.save(new Order(UUID.fromString(branchId), null, OrderType.DINE_IN, BigDecimal.ZERO));
        createdOrders.add(order);

        assertThatThrownBy(() -> jdbc.update("INSERT INTO payment (id, version, order_id, method, type, amount, currency,"
                + " created_at, updated_at) VALUES (?, 0, ?, 'CASH', 'PAYMENT', 0, 'VND', now(), now())",
                UUID.randomUUID(), order.getId()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_payment_amount_positive");
    }

    private UUID seedLegacy(JdbcTemplate scratch, boolean orphanPayment) {
        migrateTo("20.001");
        UUID companyId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        UUID branch = UUID.randomUUID();
        scratch.update("INSERT INTO " + SCHEMA + ".company (id, version, name, code, created_at, updated_at, deleted)"
                + " VALUES (?, 0, 'Legacy', 'LEGACY', now(), now(), FALSE)", companyId);
        scratch.update("INSERT INTO " + SCHEMA + ".branch (id, version, company_id, name, code, created_at, updated_at,"
                + " deleted) VALUES (?, 0, ?, 'Main', 'MAIN', now(), now(), FALSE)", branch, companyId);
        scratch.update("INSERT INTO " + SCHEMA + ".category (id, version, company_id, name, display_order, active,"
                + " deleted, created_at, updated_at) VALUES (?, 0, ?, 'Coffee', 0, TRUE, TRUE, now(), now())",
                UUID.randomUUID(), companyId);
        scratch.update("INSERT INTO " + SCHEMA + ".sales_order (id, version, branch_id, order_type, status, currency,"
                + " created_at, updated_at) VALUES (?, 0, ?, 'DINE_IN', 'PAID', 'VND', now(), now())", orderId, branch);
        scratch.update("INSERT INTO " + SCHEMA + ".payment (id, version, order_id, method, type, amount, currency,"
                + " created_at, updated_at) VALUES (?, 0, ?, 'CASH', 'PAYMENT', 100, 'VND', now(), now())",
                UUID.randomUUID(), orderId);
        // a detached order line is derived and unreachable: the migration deletes it
        scratch.update("INSERT INTO " + SCHEMA + ".order_line (id, version, product_id, product_name, quantity,"
                + " line_total) VALUES (?, 0, ?, 'Latte', 1, 10)", UUID.randomUUID(), UUID.randomUUID());
        if (orphanPayment) {
            scratch.update("INSERT INTO " + SCHEMA + ".payment (id, version, method, type, amount, currency,"
                    + " created_at, updated_at) VALUES (?, 0, 'CASH', 'PAYMENT', 5, 'VND', now(), now())",
                    UUID.randomUUID());
        }
        return companyId;
    }

    @Test
    void migration_appliesOverLegacyRows_andFreesSoftDeletedNames() {
        JdbcTemplate scratch = new JdbcTemplate(dataSource);
        UUID companyId = seedLegacy(scratch, false);

        migrateTo("21.001");

        scratch.update("INSERT INTO " + SCHEMA + ".category (id, version, company_id, name, display_order, active,"
                + " deleted, created_at, updated_at) VALUES (?, 0, ?, 'Coffee', 0, TRUE, FALSE, now(), now())",
                UUID.randomUUID(), companyId);
        assertThat(scratch.queryForObject("SELECT count(*) FROM " + SCHEMA + ".payment", Integer.class)).isEqualTo(1);
        assertThat(scratch.queryForObject("SELECT count(*) FROM " + SCHEMA + ".order_line", Integer.class)).isZero();
        assertThat(scratch.queryForObject("SELECT count(*) FROM " + SCHEMA + ".category WHERE name = 'Coffee'",
                Integer.class)).isEqualTo(2);
    }

    @Test
    void migration_stopsInsteadOfDeletingAnOrphanPayment() {
        JdbcTemplate scratch = new JdbcTemplate(dataSource);
        seedLegacy(scratch, true);

        assertThatThrownBy(() -> migrateTo("21.001"))
                .hasMessageContaining("1 payment row(s) have a NULL order_id");
        assertThat(scratch.queryForObject("SELECT count(*) FROM " + SCHEMA + ".payment", Integer.class)).isEqualTo(2);
    }

    private void migrateTo(String version) {
        Flyway.configure()
                .dataSource(dataSource)
                .schemas(SCHEMA)
                .locations("classpath:db/migration")
                .target(version)
                .load()
                .migrate();
    }
}
