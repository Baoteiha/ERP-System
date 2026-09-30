package vn.essvn.erpcafe.config;

import java.util.Arrays;
import java.util.Collection;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Class: JwtSecretGuard
 * Purpose: Refuses to start outside the dev and test profiles while the JWT secret is one committed to the repository.
 */
@Component
public class JwtSecretGuard {

    private static final String DEV_SECRET = "dev-erp-cafe-jwt-secret-change-me-in-production-0123456789";
    private static final String TEST_SECRET = "test-erp-cafe-jwt-secret-key-for-tests-0123456789";

    public JwtSecretGuard(Environment environment, SecurityProperties properties) {
        check(Arrays.asList(environment.getActiveProfiles()), properties.jwt().secret());
    }

    static void check(Collection<String> activeProfiles, String secret) {
        if (activeProfiles.contains("dev") || activeProfiles.contains("test")) {
            return;
        }
        if (DEV_SECRET.equals(secret) || TEST_SECRET.equals(secret)) {
            throw new IllegalStateException("Refusing to start: the dev JWT secret is in use but neither the "
                    + "'dev' nor 'test' profile is active. Set spring.profiles.active and JWT_SECRET.");
        }
    }
}
