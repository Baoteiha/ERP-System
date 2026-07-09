package vn.essvn.erpcafe.identity.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import vn.essvn.erpcafe.config.SecurityProperties;

class JwtServiceTest {

    private static final String SECRET = "test-erp-cafe-jwt-secret-key-for-tests-0123456789";

    private JwtService jwtService(Duration accessTtl) {
        SecurityProperties props = new SecurityProperties(
                new SecurityProperties.Jwt(SECRET, accessTtl, Duration.ofDays(30)),
                new SecurityProperties.Bootstrap("admin@test", "pw"));
        return new JwtService(props);
    }

    private AuthPrincipal samplePrincipal() {
        return new AuthPrincipal(
                UUID.randomUUID(), "user@esscafe.vn", UUID.randomUUID(),
                Set.of(UUID.randomUUID()), Set.of("branch:read", "branch:write"), Set.of("OWNER"));
    }

    @Test
    void generateThenParse_roundTripsAllClaims() {
        JwtService jwt = jwtService(Duration.ofMinutes(15));
        AuthPrincipal original = samplePrincipal();

        String token = jwt.generateAccessToken(original);
        AuthPrincipal parsed = jwt.parseAccessToken(token);

        assertThat(parsed.userId()).isEqualTo(original.userId());
        assertThat(parsed.email()).isEqualTo(original.email());
        assertThat(parsed.companyId()).isEqualTo(original.companyId());
        assertThat(parsed.branchIds()).isEqualTo(original.branchIds());
        assertThat(parsed.permissions()).isEqualTo(original.permissions());
        assertThat(parsed.roles()).isEqualTo(original.roles());
    }

    @Test
    void parse_rejectsExpiredToken() {
        JwtService jwt = jwtService(Duration.ofSeconds(-10)); // already expired
        String token = jwt.generateAccessToken(samplePrincipal());
        assertThatThrownBy(() -> jwt.parseAccessToken(token))
                .isInstanceOf(Exception.class);
    }

    @Test
    void parse_rejectsTamperedToken() {
        JwtService jwt = jwtService(Duration.ofMinutes(15));
        String token = jwt.generateAccessToken(samplePrincipal());
        String tampered = token.substring(0, token.length() - 4) + "AAAA";
        assertThatThrownBy(() -> jwt.parseAccessToken(tampered))
                .isInstanceOf(Exception.class);
    }

    @Test
    void parse_rejectsTokenSignedWithDifferentSecret() {
        String token = jwtService(Duration.ofMinutes(15)).generateAccessToken(samplePrincipal());
        SecurityProperties otherProps = new SecurityProperties(
                new SecurityProperties.Jwt("a-completely-different-secret-key-000000000", Duration.ofMinutes(15), Duration.ofDays(30)),
                new SecurityProperties.Bootstrap("a", "b"));
        JwtService otherService = new JwtService(otherProps);
        assertThatThrownBy(() -> otherService.parseAccessToken(token))
                .isInstanceOf(Exception.class);
    }
}
