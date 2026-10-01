package vn.essvn.erpcafe.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import vn.essvn.erpcafe.support.AbstractIntegrationTest;

/**
 * Class: RoleCatalogVersionMigrationTest
 * Purpose: Proves V17 marks existing default roles as synced to catalog version 1, so removals made before the upgrade stick.
 */
class RoleCatalogVersionMigrationTest extends AbstractIntegrationTest {

    /** A scratch schema in the test database, so the app's own schema is never migrated backwards. */
    private static final String SCHEMA = "legacy_role_version";

    @Autowired
    private DataSource dataSource;

    @AfterEach
    void dropSchema() {
        new JdbcTemplate(dataSource).execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
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

    @Test
    void existingDefaultRoles_startAtVersionOne_customRolesAtZero() {
        migrateTo("16.001");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        UUID companyId = UUID.randomUUID();
        jdbc.update("INSERT INTO " + SCHEMA + ".company (id, version, name, code, created_at, updated_at)"
                + " VALUES (?, 0, 'Legacy Cafe', 'LEGACY', now(), now())", companyId);
        for (String name : List.of("OWNER", "MANAGER", "CASHIER", "BARISTA")) {
            jdbc.update("INSERT INTO " + SCHEMA + ".role (id, version, company_id, name, created_at, updated_at)"
                    + " VALUES (?, 0, ?, ?, now(), now())", UUID.randomUUID(), companyId, name);
        }

        migrateTo("17.001");

        Map<String, Integer> versions = new HashMap<>();
        jdbc.queryForList("SELECT name, synced_catalog_version FROM " + SCHEMA + ".role").forEach(row ->
                versions.put((String) row.get("name"), (Integer) row.get("synced_catalog_version")));
        assertThat(versions).containsExactlyInAnyOrderEntriesOf(
                Map.of("OWNER", 1, "MANAGER", 1, "CASHIER", 1, "BARISTA", 0));
    }
}
