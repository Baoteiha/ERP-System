package vn.essvn.erpcafe.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import org.assertj.core.util.Throwables;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;

import com.zaxxer.hikari.HikariDataSource;

import vn.essvn.erpcafe.support.AbstractIntegrationTest;

/**
 * Class: UserEmailIndexMigrationTest
 * Purpose: Proves V28_002 stops and names the users when live accounts already share an email, instead of guessing.
 */
class UserEmailIndexMigrationTest extends AbstractIntegrationTest {

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
    void liveUsersSharingAnEmail_stopTheMigration_namingThem() throws Exception {
        String database = "user_email_index_" + SEQ.incrementAndGet();
        HikariDataSource owner = (HikariDataSource) ownerDataSource;
        JdbcTemplate superuser = new JdbcTemplate(ownerDataSource);
        superuser.execute("CREATE DATABASE " + database);
        try {
            String url = owner.getJdbcUrl().replaceFirst("/[^/?]+(\\?|$)", "/" + database + "$1");
            flyway(url, "28.001").migrate();
            UUID first = UUID.randomUUID();
            UUID second = UUID.randomUUID();
            UUID deleted = UUID.randomUUID();
            try (Connection connection = DriverManager.getConnection(url, owner.getUsername(), owner.getPassword());
                    Statement statement = connection.createStatement()) {
                // a database that lost the index, the only way two live accounts can share an email
                statement.execute("DROP INDEX uk_user_email_lower");
                // the company these users name is not the point
                statement.execute("SET session_replication_role = replica");
                insert(statement, first, "dup@t.test", false);
                insert(statement, second, "DUP@t.test", false);
                insert(statement, deleted, "dup@t.test", true);
            }

            Throwable refused = catchThrowable(() -> flyway(url, "28.002").migrate());

            assertThat(refused).hasStackTraceContaining("V28_002: 2 live user(s) share an email")
                    .hasStackTraceContaining(first.toString())
                    .hasStackTraceContaining(second.toString());
            assertThat(Throwables.getStackTrace(refused)).doesNotContain(deleted.toString());
        } finally {
            superuser.execute("DROP DATABASE IF EXISTS " + database + " WITH (FORCE)");
        }
    }

    private static void insert(Statement statement, UUID id, String email, boolean deleted) throws Exception {
        statement.execute("INSERT INTO app_user (id, version, company_id, email, password_hash, status, deleted,"
                + " created_at, updated_at) VALUES ('" + id + "', 0, '" + UUID.randomUUID() + "', '" + email
                + "', 'x', 'ACTIVE', " + deleted + ", now(), now())");
    }
}
