package vn.essvn.erpcafe.config;

import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.springframework.boot.flyway.autoconfigure.FlywayConfigurationCustomizer;
import org.springframework.stereotype.Component;

/**
 * Class: AppRolePasswordGuard
 * Purpose: Refuses, before Flyway runs, an app-role password that would end V22's dollar quotes early.
 *
 * <p>V22 creates erp_app inside a {@code DO $$ … $$} block, with the password between {@code $pw$}
 * quotes. A password containing {@code $$} or {@code $pw$}, or ending in {@code $}, would close a quote
 * early: the statement would fail, and PostgreSQL logs a failed statement in full, password included.
 * To keep the password out of the migration altogether, give the placeholder a SCRAM verifier
 * ({@code SCRAM-SHA-256$<iterations>:<salt>$<StoredKey>:<ServerKey>}), which PostgreSQL stores as given.
 */
@Component
public class AppRolePasswordGuard implements FlywayConfigurationCustomizer {

    private static final String PLACEHOLDER = "appRolePassword";

    @Override
    public void customize(FluentConfiguration configuration) {
        String password = configuration.getPlaceholders().get(PLACEHOLDER);
        if (password != null && !quotable(password)) {
            throw new IllegalStateException("Refusing to migrate: the app role password (spring.flyway.placeholders."
                    + PLACEHOLDER + ", from spring.datasource.password) contains '$$' or '$pw$', or ends in '$' or"
                    + " '$pw', which V22 cannot quote. Choose another password, or set the placeholder to a SCRAM"
                    + " verifier.");
        }
    }

    // V22 writes the password as $pw$-<password>$pw$ inside a DO $$ … $$ block: the first $pw$ after the
    // opening one must be the closing one, and no $$ may end the block early
    private static boolean quotable(String password) {
        String quoted = "-" + password + "$pw$";
        return quoted.indexOf("$pw$") == password.length() + 1 && !quoted.contains("$$");
    }
}
