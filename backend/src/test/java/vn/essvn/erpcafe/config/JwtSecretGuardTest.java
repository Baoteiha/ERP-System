package vn.essvn.erpcafe.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Class: JwtSecretGuardTest
 * Purpose: Proves the guard rejects the committed dev/test JWT secrets outside the dev and test profiles.
 */
class JwtSecretGuardTest {

    private static final String DEV_SECRET = "dev-erp-cafe-jwt-secret-change-me-in-production-0123456789";
    private static final String TEST_SECRET = "test-erp-cafe-jwt-secret-key-for-tests-0123456789";

    @Test
    void noActiveProfileWithDevSecretFails() {
        assertThatThrownBy(() -> JwtSecretGuard.check(List.of(), DEV_SECRET))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("dev JWT secret");
    }

    @Test
    void prodProfileWithDevSecretFails() {
        assertThatThrownBy(() -> JwtSecretGuard.check(List.of("prod"), DEV_SECRET))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void prodProfileWithTestSecretFails() {
        assertThatThrownBy(() -> JwtSecretGuard.check(List.of("prod"), TEST_SECRET))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void devProfileWithDevSecretPasses() {
        assertThatCode(() -> JwtSecretGuard.check(List.of("dev"), DEV_SECRET)).doesNotThrowAnyException();
    }

    @Test
    void testProfileWithTestSecretPasses() {
        assertThatCode(() -> JwtSecretGuard.check(List.of("test"), TEST_SECRET)).doesNotThrowAnyException();
    }

    @Test
    void prodProfileWithRealSecretPasses() {
        assertThatCode(() -> JwtSecretGuard.check(List.of("prod"), "a-real-secret-of-at-least-32-bytes-long!!"))
                .doesNotThrowAnyException();
    }
}
