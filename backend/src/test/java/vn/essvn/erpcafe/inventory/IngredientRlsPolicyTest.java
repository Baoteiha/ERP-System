package vn.essvn.erpcafe.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import vn.essvn.erpcafe.support.AbstractIntegrationTest;

/**
 * Proves the Row-Level Security policy on {@code ingredient} (Layer 2) isolates
 * tenants at the database level.
 *
 * <p>The application's own connection is the Testcontainers superuser, which
 * bypasses RLS — so this test connects as a dedicated NON-superuser role (the way
 * the app must connect in production) and verifies that a query only ever sees rows
 * for the company named in the {@code app.company_id} setting, regardless of the
 * SQL issued. Seeding is done on the superuser connection (bypassing RLS).
 */
class IngredientRlsPolicyTest extends AbstractIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final String RLS_ROLE = "erp_rls";
    private static final String RLS_PASSWORD = "erp_rls_pw";

    @Autowired
    private DataSource dataSource;

    @Test
    void rlsPolicy_isolatesByCompany() throws Exception {
        int n = SEQ.incrementAndGet();
        UUID companyA = UUID.randomUUID();
        UUID companyB = UUID.randomUUID();
        UUID ingredientA = UUID.randomUUID();
        UUID ingredientB = UUID.randomUUID();

        String jdbcUrl;
        try (Connection admin = dataSource.getConnection()) {
            jdbcUrl = admin.getMetaData().getURL();
            seedCompany(admin, companyA, "RLS Co A " + n, "RLSA" + n);
            seedCompany(admin, companyB, "RLS Co B " + n, "RLSB" + n);
            seedIngredient(admin, ingredientA, companyA, "BeansA" + n);
            seedIngredient(admin, ingredientB, companyB, "BeansB" + n);
            ensureRlsRole(admin);
        }

        // Acting as company A on a non-superuser connection: only A's ingredient is visible.
        try (Connection c = asRlsRole(jdbcUrl)) {
            setTenant(c, companyA);
            assertThat(visible(c, ingredientA)).as("A sees its own").isTrue();
            assertThat(visible(c, ingredientB)).as("A cannot see B's").isFalse();

            // Same connection, switch tenant → the visibility flips.
            setTenant(c, companyB);
            assertThat(visible(c, ingredientA)).as("B cannot see A's").isFalse();
            assertThat(visible(c, ingredientB)).as("B sees its own").isTrue();
        }

        // Fail-closed: a connection that never sets app.company_id sees nothing.
        try (Connection c = asRlsRole(jdbcUrl)) {
            assertThat(visible(c, ingredientA)).as("unset tenant sees nothing").isFalse();
            assertThat(visible(c, ingredientB)).as("unset tenant sees nothing").isFalse();
        }
    }

    // --- helpers ---

    private Connection asRlsRole(String jdbcUrl) throws Exception {
        return DriverManager.getConnection(jdbcUrl, RLS_ROLE, RLS_PASSWORD);
    }

    private void setTenant(Connection c, UUID companyId) throws Exception {
        try (PreparedStatement ps = c.prepareStatement("SELECT set_config('app.company_id', ?, false)")) {
            ps.setString(1, companyId.toString());
            ps.execute();
        }
    }

    private boolean visible(Connection c, UUID ingredientId) throws Exception {
        try (PreparedStatement ps = c.prepareStatement("SELECT count(*) FROM ingredient WHERE id = ?")) {
            ps.setObject(1, ingredientId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1) > 0;
            }
        }
    }

    private void seedCompany(Connection c, UUID id, String name, String code) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO company (id, version, name, code, deleted, created_at, updated_at) "
                        + "VALUES (?, 0, ?, ?, false, now(), now())")) {
            ps.setObject(1, id);
            ps.setString(2, name);
            ps.setString(3, code);
            ps.executeUpdate();
        }
    }

    private void seedIngredient(Connection c, UUID id, UUID companyId, String name) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO ingredient (id, version, company_id, name, base_unit, active, deleted, created_at, updated_at) "
                        + "VALUES (?, 0, ?, ?, 'g', true, false, now(), now())")) {
            ps.setObject(1, id);
            ps.setObject(2, companyId);
            ps.setString(3, name);
            ps.executeUpdate();
        }
    }

    private void ensureRlsRole(Connection c) throws Exception {
        try (Statement st = c.createStatement()) {
            st.execute("DO $$ BEGIN "
                    + "IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = '" + RLS_ROLE + "') THEN "
                    + "CREATE ROLE " + RLS_ROLE + " LOGIN PASSWORD '" + RLS_PASSWORD + "' NOSUPERUSER; "
                    + "END IF; END $$;");
            st.execute("GRANT USAGE ON SCHEMA public TO " + RLS_ROLE);
            st.execute("GRANT SELECT ON ingredient TO " + RLS_ROLE);
        }
    }
}
