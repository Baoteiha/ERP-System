package vn.essvn.erpcafe.reporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.util.Map;
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
 * Class: ReportingZoneMigrationTest
 * Purpose: Proves the report backfill refuses an offset for a zone before it cuts a single day with it.
 */
class ReportingZoneMigrationTest extends AbstractIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired
    @Qualifier("ownerDataSource")
    private DataSource ownerDataSource;

    private Flyway flyway(String url, String zone) {
        HikariDataSource owner = (HikariDataSource) ownerDataSource;
        return Flyway.configure()
                .dataSource(url, owner.getUsername(), owner.getPassword())
                .locations("classpath:db/migration")
                .placeholders(Map.of("appRolePassword", "unused-the-role-exists", "reportingZone", zone))
                .load();
    }

    @Test
    void anOffsetZone_stopsTheMigration_whileANamedZoneMigrates() {
        String database = "zone_guard_" + SEQ.incrementAndGet();
        JdbcTemplate superuser = new JdbcTemplate(ownerDataSource);
        superuser.execute("CREATE DATABASE " + database);
        try {
            String url = ((HikariDataSource) ownerDataSource).getJdbcUrl()
                    .replaceFirst("/[^/?]+(\\?|$)", "/" + database + "$1");

            Throwable refused = catchThrowable(() -> flyway(url, "+07:00").migrate());

            assertThat(refused).hasStackTraceContaining("reportingZone +07:00 is not a time zone name");
            assertThat(flyway(url, "Asia/Ho_Chi_Minh").migrate().success).isTrue();
        } finally {
            superuser.execute("DROP DATABASE IF EXISTS " + database + " WITH (FORCE)");
        }
    }
}
