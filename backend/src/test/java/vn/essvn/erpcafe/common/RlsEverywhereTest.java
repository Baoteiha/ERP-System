package vn.essvn.erpcafe.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

import javax.sql.DataSource;

import org.assertj.core.util.Throwables;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.jayway.jsonpath.JsonPath;
import com.zaxxer.hikari.HikariDataSource;

import vn.essvn.erpcafe.common.context.TenantContext;
import vn.essvn.erpcafe.common.persistence.SystemTransactions;
import vn.essvn.erpcafe.identity.persistence.RoleRepository;
import vn.essvn.erpcafe.identity.persistence.UserBranchAccessRepository;
import vn.essvn.erpcafe.support.AbstractIntegrationTest;

/**
 * Class: RlsEverywhereTest
 * Purpose: Proves row-level security binds every table a company owns, and that only the system scope sees past it.
 */
class RlsEverywhereTest extends AbstractIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final String TENANT_SETTING = "select current_setting('app.company_id', true)";
    private static final String RLS_REFUSAL = "row-level security";

    private static final String BRANCH_COMPANY = "(SELECT b.company_id FROM branch b WHERE b.id = t.branch_id)";
    private static final String ORDER_COMPANY = "(SELECT b.company_id FROM sales_order o"
            + " JOIN branch b ON b.id = o.branch_id WHERE o.id = t.order_id)";

    /** Every table a company owns, and the company each row belongs to: independent of the policies it checks. */
    private static final Map<String, String> COMPANY_OF = new LinkedHashMap<>();

    static {
        COMPANY_OF.put("company", "t.id");
        for (String table : List.of("branch", "role", "app_user", "category", "modifier_group", "product",
                "ingredient", "supplier", "item", "employee", "shift")) {
            COMPANY_OF.put(table, "t.company_id");
        }
        for (String table : List.of("user_branch_access", "product_branch_availability", "stock_item",
                "stock_movement", "purchase_order", "sales_order", "ledger_reference")) {
            COMPANY_OF.put(table, BRANCH_COMPANY);
        }
        COMPANY_OF.put("role_permission", "(SELECT r.company_id FROM role r WHERE r.id = t.role_id)");
        COMPANY_OF.put("refresh_token", "(SELECT u.company_id FROM app_user u WHERE u.id = t.user_id)");
        COMPANY_OF.put("modifier", "(SELECT g.company_id FROM modifier_group g WHERE g.id = t.modifier_group_id)");
        COMPANY_OF.put("modifier_recipe_line", "(SELECT g.company_id FROM modifier m"
                + " JOIN modifier_group g ON g.id = m.modifier_group_id WHERE m.id = t.modifier_id)");
        COMPANY_OF.put("product_modifier_group", "(SELECT p.company_id FROM product p WHERE p.id = t.product_id)");
        COMPANY_OF.put("recipe", "(SELECT p.company_id FROM product p WHERE p.id = t.product_id)");
        COMPANY_OF.put("recipe_line", "(SELECT p.company_id FROM recipe r JOIN product p ON p.id = r.product_id"
                + " WHERE r.id = t.recipe_id)");
        COMPANY_OF.put("purchase_order_line", "(SELECT b.company_id FROM purchase_order o"
                + " JOIN branch b ON b.id = o.branch_id WHERE o.id = t.purchase_order_id)");
        COMPANY_OF.put("order_line", ORDER_COMPANY);
        COMPANY_OF.put("order_line_modifier", "(SELECT b.company_id FROM order_line l"
                + " JOIN sales_order o ON o.id = l.order_id JOIN branch b ON b.id = o.branch_id"
                + " WHERE l.id = t.order_line_id)");
        COMPANY_OF.put("payment", ORDER_COMPANY);
    }

    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private SystemTransactions system;
    @Autowired private UserBranchAccessRepository accessRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired @Qualifier("ownerDataSource") private DataSource ownerDataSource;

    /** One company's row in every table it can own; the ids a test needs to aim at them. */
    private record Seeded(UUID company, UUID branch, UUID user, UUID ingredient, UUID order, UUID payment) {
    }

    private JdbcTemplate owner() {
        return new JdbcTemplate(ownerDataSource);
    }

    private <T> T inTransaction(Supplier<T> work) {
        return new TransactionTemplate(transactionManager).execute(status -> work.get());
    }

    private String settingInATransaction() {
        return inTransaction(() -> jdbc.queryForObject(TENANT_SETTING, String.class));
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
    }

    private long ownedBy(String table, UUID company) {
        return owner().queryForObject("SELECT count(*) FROM " + table + " t WHERE " + COMPANY_OF.get(table) + " = ?",
                Long.class, company);
    }

    // Written as the owner, past the policies, so the fixture does not depend on what it tests.
    private Seeded seedEveryTable() {
        int n = SEQ.incrementAndGet();
        JdbcTemplate db = owner();
        UUID company = UUID.randomUUID();
        UUID branch = UUID.randomUUID();
        UUID role = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        UUID category = UUID.randomUUID();
        UUID group = UUID.randomUUID();
        UUID modifier = UUID.randomUUID();
        UUID product = UUID.randomUUID();
        UUID recipe = UUID.randomUUID();
        UUID ingredient = UUID.randomUUID();
        UUID supplier = UUID.randomUUID();
        UUID purchaseOrder = UUID.randomUUID();
        UUID order = UUID.randomUUID();
        UUID line = UUID.randomUUID();
        UUID payment = UUID.randomUUID();
        UUID employee = UUID.randomUUID();
        String audit = "created_at, updated_at) VALUES (";

        db.update("INSERT INTO company (id, version, name, code, " + audit + "?, 0, ?, ?, now(), now())",
                company, "Rls Co " + n, "RLS" + n);
        db.update("INSERT INTO branch (id, version, company_id, name, code, " + audit + "?, 0, ?, 'Main', 'MAIN',"
                + " now(), now())", branch, company);
        db.update("INSERT INTO role (id, version, company_id, name, " + audit + "?, 0, ?, 'CREW', now(), now())",
                role, company);
        db.update("INSERT INTO role_permission (role_id, permission_id) SELECT ?, id FROM permission LIMIT 1", role);
        db.update("INSERT INTO app_user (id, version, company_id, email, password_hash, status, " + audit
                + "?, 0, ?, ?, 'x', 'ACTIVE', now(), now())", user, company, "rls" + n + "@rls.test");
        db.update("INSERT INTO user_branch_access (id, version, user_id, branch_id, role_id, " + audit
                + "?, 0, ?, ?, ?, now(), now())", UUID.randomUUID(), user, branch, role);
        db.update("INSERT INTO refresh_token (id, version, user_id, token_hash, expires_at, family_id)"
                + " VALUES (?, 0, ?, ?, now(), ?)",
                UUID.randomUUID(), user, "rls-" + UUID.randomUUID(), UUID.randomUUID());
        db.update("INSERT INTO category (id, version, company_id, name, " + audit + "?, 0, ?, 'Drinks', now(), now())",
                category, company);
        db.update("INSERT INTO modifier_group (id, version, company_id, name, " + audit
                + "?, 0, ?, 'Size', now(), now())", group, company);
        db.update("INSERT INTO modifier (id, version, modifier_group_id, name, " + audit
                + "?, 0, ?, 'Large', now(), now())", modifier, group);
        db.update("INSERT INTO ingredient (id, version, company_id, name, base_unit, " + audit
                + "?, 0, ?, 'Beans', 'g', now(), now())", ingredient, company);
        db.update("INSERT INTO modifier_recipe_line (id, version, modifier_id, ingredient_id, quantity_delta, unit)"
                + " VALUES (?, 0, ?, ?, 1, 'g')", UUID.randomUUID(), modifier, ingredient);
        db.update("INSERT INTO product (id, version, company_id, category_id, name, sku, " + audit
                + "?, 0, ?, ?, 'Latte', 'LAT', now(), now())", product, company, category);
        db.update("INSERT INTO product_modifier_group (product_id, modifier_group_id) VALUES (?, ?)", product, group);
        db.update("INSERT INTO product_branch_availability (id, version, branch_id, product_id, " + audit
                + "?, 0, ?, ?, now(), now())", UUID.randomUUID(), branch, product);
        db.update("INSERT INTO recipe (id, version, product_id, " + audit + "?, 0, ?, now(), now())", recipe, product);
        db.update("INSERT INTO recipe_line (id, version, recipe_id, ingredient_id, quantity, unit)"
                + " VALUES (?, 0, ?, ?, 18, 'g')", UUID.randomUUID(), recipe, ingredient);
        db.update("INSERT INTO supplier (id, version, company_id, name, " + audit + "?, 0, ?, 'Roaster', now(), now())",
                supplier, company);
        db.update("INSERT INTO item (id, version, company_id, name, supplier_id, ingredient_id, unit, " + audit
                + "?, 0, ?, 'Beans 1kg', ?, ?, 'kg', now(), now())", UUID.randomUUID(), company, supplier, ingredient);
        db.update("INSERT INTO stock_item (id, version, branch_id, ingredient_id, " + audit
                + "?, 0, ?, ?, now(), now())", UUID.randomUUID(), branch, ingredient);
        db.update("INSERT INTO stock_movement (id, version, branch_id, ingredient_id, type, quantity, " + audit
                + "?, 0, ?, ?, 'ADJUSTMENT', 5, now(), now())", UUID.randomUUID(), branch, ingredient);
        db.update("INSERT INTO purchase_order (id, version, branch_id, supplier_id, status, " + audit
                + "?, 0, ?, ?, 'DRAFT', now(), now())", purchaseOrder, branch, supplier);
        db.update("INSERT INTO purchase_order_line (id, version, purchase_order_id, ingredient_id, ordered_qty)"
                + " VALUES (?, 0, ?, ?, 1000)", UUID.randomUUID(), purchaseOrder, ingredient);
        db.update("INSERT INTO sales_order (id, version, branch_id, order_type, status, currency, " + audit
                + "?, 0, ?, 'DINE_IN', 'PAID', 'VND', now(), now())", order, branch);
        db.update("INSERT INTO order_line (id, version, order_id, product_id, product_name, quantity, line_total)"
                + " VALUES (?, 0, ?, ?, 'Latte', 1, 45000)", line, order, product);
        db.update("INSERT INTO order_line_modifier (id, version, order_line_id, modifier_name)"
                + " VALUES (?, 0, ?, 'Large')", UUID.randomUUID(), line);
        db.update("INSERT INTO payment (id, version, order_id, method, type, amount, " + audit
                + "?, 0, ?, 'CASH', 'PAYMENT', 45000, now(), now())", payment, order);
        db.update("INSERT INTO ledger_reference (ref_type, ref_id, branch_id) VALUES ('SALE', ?, ?)", order, branch);
        db.update("INSERT INTO employee (id, version, company_id, full_name, " + audit
                + "?, 0, ?, 'Linh', now(), now())", employee, company);
        db.update("INSERT INTO shift (id, version, company_id, branch_id, employee_id, status, scheduled_start,"
                + " scheduled_end, " + audit + "?, 0, ?, ?, ?, 'SCHEDULED', now(), now() + interval '8 hours',"
                + " now(), now())", UUID.randomUUID(), company, branch, employee);
        return new Seeded(company, branch, user, ingredient, order, payment);
    }

    // --- every transaction begins in its company ---

    @Test
    void everyTransaction_beginsInTheCompanyInForce() {
        UUID company = UUID.randomUUID();

        assertThat(TenantContext.runAs(company, this::settingInATransaction)).isEqualTo(company.toString());
    }

    @Test
    void aNewTransactionInsideAnother_beginsInTheCompanyToo() {
        UUID company = UUID.randomUUID();
        TransactionTemplate requiresNew = new TransactionTemplate(transactionManager);
        requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        String inner = TenantContext.runAs(company, () -> inTransaction(
                () -> requiresNew.execute(status -> jdbc.queryForObject(TENANT_SETTING, String.class))));

        assertThat(inner).isEqualTo(company.toString());
    }

    @Test
    void withNoCompany_noneIsSet() {
        // A pooled connection reads the setting back as "" once a past transaction set it; both mean none.
        assertThat(settingInATransaction()).isNullOrEmpty();
        assertThat(TenantContext.runAsSystem(this::settingInATransaction)).isNullOrEmpty();
    }

    @Test
    void theCompany_endsWithItsTransaction() {
        UUID company = UUID.randomUUID();
        TenantContext.runAs(company, this::settingInATransaction);

        assertThat(settingInATransaction()).isNullOrEmpty();
    }

    // --- the policies ---

    @Test
    void everyTableACompanyCanOwn_isUnderForcedRowLevelSecurity() {
        List<String> tables = jdbc.queryForList("SELECT c.relname FROM pg_class c"
                + " JOIN pg_namespace ns ON ns.oid = c.relnamespace"
                + " WHERE ns.nspname = current_schema() AND c.relkind = 'r'"
                + " AND c.relname NOT IN ('permission', 'flyway_schema_history')", String.class);
        assertThat(tables).containsExactlyInAnyOrderElementsOf(COMPANY_OF.keySet());

        for (String table : tables) {
            Map<String, Object> rls = jdbc.queryForMap("SELECT c.relrowsecurity AS enabled,"
                    + " c.relforcerowsecurity AS forced,"
                    + " (SELECT count(*) FROM pg_policy p WHERE p.polrelid = c.oid) AS policies"
                    + " FROM pg_class c WHERE c.oid = ?::regclass", table);
            assertThat(rls).as(table).containsEntry("enabled", true).containsEntry("forced", true);
            assertThat((Long) rls.get("policies")).as(table).isPositive();
        }
    }

    @Test
    void theAppRole_onlyReadsThePermissionCatalog_andCannotTouchTheMigrationHistory() {
        assertThat(jdbc.queryForObject("SELECT has_table_privilege('permission', 'SELECT')", Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("SELECT has_table_privilege('permission', 'INSERT, UPDATE, DELETE')",
                Boolean.class)).isFalse();
        assertThat(jdbc.queryForObject("SELECT has_table_privilege('flyway_schema_history',"
                + " 'SELECT, INSERT, UPDATE, DELETE')", Boolean.class)).isFalse();
    }

    @Test
    void aCompany_seesExactlyItsOwnRows_inEveryTable() {
        Seeded a = seedEveryTable();
        Seeded b = seedEveryTable();

        TenantContext.runAs(a.company(), () -> inTransaction(() -> {
            for (String table : COMPANY_OF.keySet()) {
                long own = ownedBy(table, a.company());
                assertThat(own).as("%s holds a row of the company", table).isPositive();
                assertThat(ownedBy(table, b.company())).as("%s holds another company's row", table).isPositive();
                assertThat(count(table)).as("rows of %s the company sees", table).isEqualTo(own);
            }
            return null;
        }));
    }

    @Test
    void aRepositoryQuery_calledOutsideAnyTransaction_stillRunsInTheCompany() {
        Seeded a = seedEveryTable();

        assertThat(TenantContext.runAs(a.company(), () -> accessRepository.findByUserId(a.user()))).hasSize(1);
    }

    @Test
    void aLockQuery_outsideATransaction_isRefused() {
        assertThatThrownBy(() -> TenantContext.runAs(UUID.randomUUID(),
                () -> roleRepository.lockByIdAndCompanyId(UUID.randomUUID(), UUID.randomUUID())))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void aSystemTransactionInsideAnother_isRefused() {
        // it would hold a second owner connection while the first is still taken
        assertThatThrownBy(() -> system.run(() -> system.run(() -> 1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already open");
    }

    @Test
    void withNoCompany_everyTableLooksEmpty() {
        seedEveryTable();

        for (String table : COMPANY_OF.keySet()) {
            assertThat(inTransaction(() -> count(table))).as(table).isZero();
            assertThat(count(table)).as("%s outside a transaction", table).isZero();
        }
    }

    @Test
    void theSystemScope_seesEveryCompany_asTheOwner() {
        Seeded a = seedEveryTable();
        Seeded b = seedEveryTable();

        String role = system.run(() -> jdbc.queryForObject("SELECT current_user", String.class));
        long seen = system.run(() -> jdbc.queryForObject("SELECT count(*) FROM payment WHERE id IN (?, ?)",
                Long.class, a.payment(), b.payment()));

        assertThat(role).isNotEqualTo("erp_app").isEqualTo(((HikariDataSource) ownerDataSource).getUsername());
        assertThat(seen).isEqualTo(2);
        assertThat(inTransaction(() -> jdbc.queryForObject("SELECT current_user", String.class))).isEqualTo("erp_app");
    }

    @Test
    void aCompany_cannotWriteAnotherCompanysRows() {
        Seeded a = seedEveryTable();
        Seeded b = seedEveryTable();

        TenantContext.runAs(a.company(), () -> {
            assertThatThrownBy(() -> inTransaction(() -> jdbc.update("INSERT INTO ingredient (id, version, company_id,"
                    + " name, base_unit, created_at, updated_at) VALUES (?, 0, ?, 'Forged', 'g', now(), now())",
                    UUID.randomUUID(), b.company()))).rootCause().hasMessageContaining(RLS_REFUSAL);
            assertThatThrownBy(() -> inTransaction(() -> jdbc.update("INSERT INTO stock_movement (id, version,"
                    + " branch_id, ingredient_id, type, quantity, created_at, updated_at)"
                    + " VALUES (?, 0, ?, ?, 'ADJUSTMENT', 99, now(), now())",
                    UUID.randomUUID(), b.branch(), b.ingredient()))).rootCause().hasMessageContaining(RLS_REFUSAL);
            assertThatThrownBy(() -> inTransaction(() -> jdbc.update("INSERT INTO payment (id, version, order_id,"
                    + " method, type, amount, created_at, updated_at) VALUES (?, 0, ?, 'CASH', 'REFUND', 45000,"
                    + " now(), now())", UUID.randomUUID(), b.order()))).rootCause().hasMessageContaining(RLS_REFUSAL);
            // moving its own row into the other company is a write outside it too
            assertThatThrownBy(() -> inTransaction(() -> jdbc.update(
                    "UPDATE stock_item SET branch_id = ? WHERE branch_id = ?", b.branch(), a.branch())))
                    .rootCause().hasMessageContaining(RLS_REFUSAL);

            assertThat(inTransaction(() -> jdbc.update("UPDATE ingredient SET name = 'Taken' WHERE id = ?",
                    b.ingredient()))).isZero();
            assertThat(inTransaction(() -> jdbc.update("DELETE FROM payment WHERE id = ?", b.payment()))).isZero();
        });

        assertThat(owner().queryForObject("SELECT name FROM ingredient WHERE id = ?", String.class, b.ingredient()))
                .isEqualTo("Beans");
        assertThat(owner().queryForObject("SELECT count(*) FROM payment WHERE order_id = ?", Long.class, b.order()))
                .isEqualTo(1);
        assertThat(owner().queryForObject("SELECT count(*) FROM stock_movement WHERE branch_id = ?", Long.class,
                b.branch())).isEqualTo(1);
    }

    // --- the paths that run before a company is known ---

    @Test
    void login_refresh_andLogout_stillWork() throws Exception {
        String refresh = JsonPath.read(loginBody("admin@esscafe.vn", "admin1234"), "$.refreshToken");

        String rotated = JsonPath.read(mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"%s\"}".formatted(refresh)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), "$.refreshToken");
        mockMvc.perform(post("/api/v1/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"%s\"}".formatted(rotated)))
                .andExpect(status().isNoContent());

        assertThat(owner().queryForObject("SELECT bool_and(revoked) FROM refresh_token WHERE family_id ="
                + " (SELECT family_id FROM refresh_token WHERE token_hash = encode(sha256(?::bytea), 'hex'))",
                Boolean.class, rotated)).isTrue();
    }

    @Test
    void theAppStarted_onAnEmptyDatabaseMigratedByTheOwner() {
        String installer = owner().queryForObject(
                "SELECT installed_by FROM flyway_schema_history WHERE version = '22.001'", String.class);

        assertThat(installer).isEqualTo(((HikariDataSource) ownerDataSource).getUsername());
        assertThat(jdbc.queryForObject("SELECT current_user", String.class)).isEqualTo("erp_app");
    }

    /** Runs work on a new, empty database owned by a new NOSUPERUSER BYPASSRLS role, and drops both after. */
    private void inAFreshDatabase(BiConsumer<String, String> work) {
        int n = SEQ.incrementAndGet();
        String role = "rls_owner_" + n;
        String database = "rls_fresh_" + n;
        JdbcTemplate superuser = owner();
        superuser.execute("CREATE ROLE " + role + " LOGIN PASSWORD 'owner' NOSUPERUSER BYPASSRLS");
        superuser.execute("CREATE DATABASE " + database + " OWNER " + role);
        try {
            String url = ((HikariDataSource) ownerDataSource).getJdbcUrl()
                    .replaceFirst("/[^/?]+(\\?|$)", "/" + database + "$1");
            work.accept(url, role);
        } finally {
            superuser.execute("DROP DATABASE IF EXISTS " + database + " WITH (FORCE)");
            superuser.execute("DROP ROLE IF EXISTS " + role);
        }
    }

    private Flyway flyway(String url, String role, String appRolePassword, String target) {
        FluentConfiguration config = Flyway.configure()
                .dataSource(url, role, "owner")
                .locations("classpath:db/migration")
                .placeholders(Map.of("appRolePassword", appRolePassword));
        return (target == null ? config : config.target(target)).load();
    }

    @Test
    void anEmptyDatabase_migratesUnderANonSuperuserOwnerWithBypassRls() {
        inAFreshDatabase((url, role) -> {
            assertThat(flyway(url, role, "unused-the-role-exists", null).migrate().success).isTrue();
            // the password is not part of a migration's checksum: changing it never fails validation
            flyway(url, role, "rotated", null).validate();
        });
    }

    @Test
    void anOwnerWithoutBypassRls_isRefusedBeforeTheAppRolePasswordIsSent() {
        String secret = "must-not-leak-" + UUID.randomUUID();
        inAFreshDatabase((url, role) -> {
            flyway(url, role, "unused", "21.001").migrate();
            owner().execute("ALTER ROLE " + role + " NOBYPASSRLS");

            Throwable refused = catchThrowable(() -> flyway(url, role, secret, null).migrate());

            String refusal = "Flyway runs as " + role + ", which lacks BYPASSRLS";
            assertThat(refused).hasStackTraceContaining(refusal);
            assertThat(Throwables.getStackTrace(refused)).doesNotContain(secret);
            // the server logs a failed statement in full: the one that failed must not carry the password
            assertThat(serverLogOnceItShows(refusal)).doesNotContain(secret);
        });
    }

    private String serverLogOnceItShows(String text) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        String log = databaseLog();
        while (!log.contains(text) && System.nanoTime() < deadline) {
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(100));
            log = databaseLog();
        }
        assertThat(log).contains(text);
        return log;
    }
}
