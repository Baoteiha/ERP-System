package vn.essvn.erpcafe.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Class: AppRolePasswordGuardTest
 * Purpose: Proves an app-role password that would end V22's dollar quotes early is refused before Flyway runs.
 */
class AppRolePasswordGuardTest {

    private static FluentConfiguration withPassword(String password) {
        return Flyway.configure().placeholders(Map.of("appRolePassword", password));
    }

    @ParameterizedTest
    @ValueSource(strings = {"pa$$word", "ends-in-dollar$", "has-$pw$-inside"})
    void aPasswordThatWouldEndAQuote_isRefused_withoutBeingNamed(String password) {
        assertThatThrownBy(() -> new AppRolePasswordGuard().customize(withPassword(password)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("appRolePassword")
                .hasMessageNotContaining(password);
    }

    @ParameterizedTest
    @ValueSource(strings = {"erp-app-test", "a$b", "$leading", "SCRAM-SHA-256$4096:c2FsdA==$c3RvcmVk:c2VydmVy"})
    void anyOtherPassword_andAScramVerifier_pass(String password) {
        assertThatCode(() -> new AppRolePasswordGuard().customize(withPassword(password))).doesNotThrowAnyException();
    }

    @Test
    void noPassword_isLeftToV22() {
        assertThatCode(() -> new AppRolePasswordGuard().customize(Flyway.configure())).doesNotThrowAnyException();
    }
}
