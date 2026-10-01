package vn.essvn.erpcafe.identity.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import vn.essvn.erpcafe.config.SecurityProperties;

class JwtServiceTest {

    private static final String SECRET = "test-erp-cafe-jwt-secret-key-for-tests-0123456789";

    private JwtService jwtService(Duration accessTtl) {
        SecurityProperties props = new SecurityProperties(
                new SecurityProperties.Jwt(SECRET, accessTtl, Duration.ofDays(30)),
                new SecurityProperties.Bootstrap("admin@test", "pw"), null);
        return new JwtService(props);
    }

    private AccessClaims sampleClaims() {
        return new AccessClaims(UUID.randomUUID(), "user@esscafe.vn", UUID.randomUUID(),
                Map.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()));
    }

    @Test
    void generateThenParse_roundTripsAllClaims() {
        JwtService jwt = jwtService(Duration.ofMinutes(15));
        AccessClaims original = sampleClaims();

        String token = jwt.generateAccessToken(original);

        assertThat(jwt.parseAccessToken(token)).isEqualTo(original);
    }

    @Test
    void generateThenParse_keepsAUserWithNoBranches() {
        JwtService jwt = jwtService(Duration.ofMinutes(15));
        AccessClaims none = new AccessClaims(UUID.randomUUID(), "new@esscafe.vn", UUID.randomUUID(), Map.of());

        assertThat(jwt.parseAccessToken(jwt.generateAccessToken(none))).isEqualTo(none);
    }

    @Test
    void parse_rejectsATokenWithoutBranchRoles() {
        String legacy = Jwts.builder()
                .subject(UUID.randomUUID().toString())
                .claim("email", "old@esscafe.vn")
                .claim("companyId", UUID.randomUUID().toString())
                .claim("perms", List.of("sales:refund"))
                .expiration(Date.from(Instant.now().plus(Duration.ofMinutes(5))))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
                .compact();

        assertThatThrownBy(() -> jwtService(Duration.ofMinutes(15)).parseAccessToken(legacy))
                .isInstanceOf(Exception.class);
    }

    @Test
    void parse_rejectsExpiredToken() {
        JwtService jwt = jwtService(Duration.ofSeconds(-10)); // already expired
        String token = jwt.generateAccessToken(sampleClaims());
        assertThatThrownBy(() -> jwt.parseAccessToken(token))
                .isInstanceOf(Exception.class);
    }

    @Test
    void parse_rejectsTamperedToken() {
        JwtService jwt = jwtService(Duration.ofMinutes(15));
        String token = jwt.generateAccessToken(sampleClaims());
        String tampered = token.substring(0, token.length() - 4) + "AAAA";
        assertThatThrownBy(() -> jwt.parseAccessToken(tampered))
                .isInstanceOf(Exception.class);
    }

    @Test
    void parse_rejectsTokenSignedWithDifferentSecret() {
        String token = jwtService(Duration.ofMinutes(15)).generateAccessToken(sampleClaims());
        SecurityProperties otherProps = new SecurityProperties(
                new SecurityProperties.Jwt("a-completely-different-secret-key-000000000", Duration.ofMinutes(15), Duration.ofDays(30)),
                new SecurityProperties.Bootstrap("a", "b"), null);
        JwtService otherService = new JwtService(otherProps);
        assertThatThrownBy(() -> otherService.parseAccessToken(token))
                .isInstanceOf(Exception.class);
    }
}
