package vn.essvn.erpcafe.config;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Set;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Class: JwtSecretGuard
 * Purpose: Refuses to start outside the dev and test profiles while a JWT signing key is one committed to the repository.
 */
@Component
public class JwtSecretGuard {

    private static final Set<String> COMMITTED_SECRETS = Set.of(
            "dev-erp-cafe-jwt-secret-change-me-in-production-0123456789",
            "test-erp-cafe-jwt-secret-key-for-tests-0123456789",
            "test-erp-cafe-retired-jwt-key-for-tests-0123456789");

    public JwtSecretGuard(Environment environment, SecurityProperties properties) {
        List<String> profiles = Arrays.asList(environment.getActiveProfiles());
        properties.jwt().keys().forEach(key -> check(profiles, key.secret()));
    }

    static void check(Collection<String> activeProfiles, String secret) {
        if (activeProfiles.contains("dev") || activeProfiles.contains("test")) {
            return;
        }
        if (COMMITTED_SECRETS.contains(secret)) {
            throw new IllegalStateException("Refusing to start: the dev JWT secret is in use but neither the "
                    + "'dev' nor 'test' profile is active. Set spring.profiles.active and JWT_SECRET.");
        }
    }
}
