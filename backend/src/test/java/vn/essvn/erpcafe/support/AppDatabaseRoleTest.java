package vn.essvn.erpcafe.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

/**
 * The application's own database identity (plan task B1). Row-level security
 * is only real if the runtime connection cannot bypass it: superusers and
 * BYPASSRLS roles ignore every policy, which is why RLS has been dormant in
 * dev. These tests pin the app datasource to an unprivileged role and prove
 * the ingredient policy actually filters what the app connection sees.
 */
class AppDatabaseRoleTest extends AbstractIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired
    private DataSource dataSource;

    @Test
    void appRole_isNotSuperuserAndCannotBypassRls() throws Exception {
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            ResultSet rs = st.executeQuery(
                    "SELECT current_user, rolsuper, rolbypassrls FROM pg_roles WHERE rolname = current_user");
            assertThat(rs.next()).isTrue();
            String user = rs.getString(1);
            assertThat(rs.getBoolean(2)).as("app role %s must not be a superuser", user).isFalse();
            assertThat(rs.getBoolean(3)).as("app role %s must not hold BYPASSRLS", user).isFalse();
        }
    }

    @Test
    void appConnection_withNoTenantSet_seesNoIngredients() throws Exception {
        // Put at least one real row in the table through the API (the request's
        // transaction carries its tenant, so the write passes the policy).
        String token = accessToken("admin@esscafe.vn", "admin1234");
        mockMvc.perform(post("/api/v1/ingredients")
                        .header(HttpHeaders.AUTHORIZATION, authHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"RoleProof%d\",\"baseUnit\":\"g\"}".formatted(SEQ.incrementAndGet())))
                .andExpect(status().isCreated());

        // A fresh app connection with no app.company_id set must see an empty table —
        // fail-closed. A superuser connection would see every row instead.
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            ResultSet rs = st.executeQuery("SELECT count(*) FROM ingredient");
            assertThat(rs.next()).isTrue();
            assertThat(rs.getLong(1))
                    .as("no tenant set on the connection → the policy must hide every row")
                    .isZero();
        }
    }
}
