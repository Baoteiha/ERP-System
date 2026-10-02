package vn.essvn.erpcafe.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;

import com.zaxxer.hikari.HikariDataSource;

import vn.essvn.erpcafe.support.AbstractIntegrationTest;

/**
 * Class: RefreshTokenRevocationMigrationTest
 * Purpose: Proves V27 revokes every refresh token that was live before the cookie deploy and leaves the spent ones alone.
 */
class RefreshTokenRevocationMigrationTest extends AbstractIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired
    @Qualifier("ownerDataSource")
    private DataSource ownerDataSource;

    private Flyway flyway(String url, String target) {
        HikariDataSource owner = (HikariDataSource) ownerDataSource;
        return Flyway.configure()
                .dataSource(url, owner.getUsername(), owner.getPassword())
                .locations("classpath:db/migration")
                .placeholders(Map.of("appRolePassword", "unused-the-role-exists", "reportingZone", "Asia/Ho_Chi_Minh"))
                .target(target)
                .load();
    }

    /** A database of its own, so the app's schema is never migrated backwards. */
    @Test
    void aTokenLiveBeforeTheMigration_isRevokedAfterIt_andItsVersionMoves() throws Exception {
        String database = "refresh_revocation_" + SEQ.incrementAndGet();
        HikariDataSource owner = (HikariDataSource) ownerDataSource;
        JdbcTemplate superuser = new JdbcTemplate(ownerDataSource);
        superuser.execute("CREATE DATABASE " + database);
        try {
            String url = owner.getJdbcUrl().replaceFirst("/[^/?]+(\\?|$)", "/" + database + "$1");
            flyway(url, "26.006").migrate();
            UUID live = UUID.randomUUID();
            UUID spent = UUID.randomUUID();
            try (Connection connection = DriverManager.getConnection(url, owner.getUsername(), owner.getPassword());
                    Statement statement = connection.createStatement()) {
                // the users these tokens name are not the point
                statement.execute("SET session_replication_role = replica");
                insert(statement, live, false);
                insert(statement, spent, true);
            }

            flyway(url, "27.001").migrate();

            try (Connection connection = DriverManager.getConnection(url, owner.getUsername(), owner.getPassword());
                    Statement statement = connection.createStatement()) {
                assertThat(row(statement, live)).isEqualTo("true,1");
                // already revoked: not touched again
                assertThat(row(statement, spent)).isEqualTo("true,0");
            }

            flyway(url, "28.001").migrate();

            try (Connection connection = DriverManager.getConnection(url, owner.getUsername(), owner.getPassword());
                    Statement statement = connection.createStatement();
                    ResultSet rotated = statement.executeQuery("SELECT count(*) FROM refresh_token"
                            + " WHERE rotated_at IS NOT NULL")) {
                // revoked, not rotated away: presenting one is refused like an expired token, never as a replay
                rotated.next();
                assertThat(rotated.getLong(1)).isZero();
            }
        } finally {
            superuser.execute("DROP DATABASE IF EXISTS " + database + " WITH (FORCE)");
        }
    }

    private static String row(Statement statement, UUID id) throws Exception {
        try (ResultSet rs = statement.executeQuery("SELECT revoked, version FROM refresh_token WHERE id = '" + id + "'")) {
            rs.next();
            return rs.getBoolean(1) + "," + rs.getLong(2);
        }
    }

    private static void insert(Statement statement, UUID id, boolean revoked) throws Exception {
        statement.execute("INSERT INTO refresh_token (id, version, user_id, token_hash, expires_at,"
                + " revoked, family_id) VALUES ('" + id + "', 0, '" + UUID.randomUUID() + "', '" + id
                + "', '" + Instant.now().plusSeconds(86400) + "', " + revoked + ", '" + id + "')");
    }
}
