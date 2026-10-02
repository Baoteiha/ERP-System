package vn.essvn.erpcafe.identity.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.UnsupportedJwtException;
import io.jsonwebtoken.security.Keys;
import vn.essvn.erpcafe.config.SecurityProperties;
import vn.essvn.erpcafe.config.SecurityProperties.Key;

class JwtServiceTest {

    private static final String SECRET = "test-erp-cafe-jwt-secret-key-for-tests-0123456789";
    private static final String NEXT_SECRET = "the-next-erp-cafe-jwt-secret-key-for-tests-0123456789";
    private static final Key CURRENT = new Key("k1", SECRET, true);

    private static SecurityProperties props(Duration accessTtl, Key... keys) {
        return new SecurityProperties(new SecurityProperties.Jwt(List.of(keys), accessTtl, Duration.ofDays(30)),
                new SecurityProperties.Bootstrap("admin@test", "pw"), null, null);
    }

    private JwtService jwtService(Duration accessTtl) {
        return new JwtService(props(accessTtl, CURRENT));
    }

    private AccessClaims sampleClaims() {
        return new AccessClaims(UUID.randomUUID(), "user@esscafe.vn", UUID.randomUUID(),
                Map.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()), 3);
    }

    /** A token as this service would issue it, with one header or claim changed. */
    private static String handMade(String kid, String secret, String issuer, String audience) {
        AccessClaims claims = new AccessClaims(UUID.randomUUID(), "user@esscafe.vn", UUID.randomUUID(), Map.of(), 0);
        return Jwts.builder()
                .header().keyId(kid).and()
                .issuer(issuer)
                .audience().add(audience).and()
                .subject(claims.userId().toString())
                .claim("email", claims.email())
                .claim("companyId", claims.companyId().toString())
                .claim("branchRoles", Map.of())
                .claim("av", claims.authVersion())
                .expiration(Date.from(Instant.now().plus(Duration.ofMinutes(5))))
                .signWith(Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8)))
                .compact();
    }

    @Test
    void generateThenParse_roundTripsAllClaims() {
        JwtService jwt = jwtService(Duration.ofMinutes(15));
        AccessClaims original = sampleClaims();

        String token = jwt.generateAccessToken(original);

        AccessClaims parsed = jwt.parseAccessToken(token);
        assertThat(parsed).usingRecursiveComparison().ignoringFields("issuedAt").isEqualTo(original);
        assertThat(parsed.issuedAt()).isCloseTo(Instant.now(), within(Duration.ofSeconds(5)));
    }

    @Test
    void generateThenParse_keepsAUserWithNoBranches() {
        JwtService jwt = jwtService(Duration.ofMinutes(15));
        AccessClaims none = new AccessClaims(UUID.randomUUID(), "new@esscafe.vn", UUID.randomUUID(), Map.of(), 0);

        assertThat(jwt.parseAccessToken(jwt.generateAccessToken(none)))
                .usingRecursiveComparison().ignoringFields("issuedAt").isEqualTo(none);
    }

    @Test
    void parse_rejectsATokenWithoutBranchRoles() {
        String legacy = Jwts.builder()
                .header().keyId(CURRENT.kid()).and()
                .issuer(JwtService.ISSUER)
                .audience().add(JwtService.AUDIENCE).and()
                .subject(UUID.randomUUID().toString())
                .claim("email", "old@esscafe.vn")
                .claim("companyId", UUID.randomUUID().toString())
                .claim("perms", List.of("sales:refund"))
                .expiration(Date.from(Instant.now().plus(Duration.ofMinutes(5))))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
                .compact();

        assertThatThrownBy(() -> jwtService(Duration.ofMinutes(15)).parseAccessToken(legacy))
                .isInstanceOf(MalformedJwtException.class)
                .hasMessageContaining("branch roles");
    }

    @Test
    void parse_rejectsATokenWithoutAnAuthVersion() {
        String unversioned = Jwts.builder()
                .header().keyId(CURRENT.kid()).and()
                .issuer(JwtService.ISSUER)
                .audience().add(JwtService.AUDIENCE).and()
                .subject(UUID.randomUUID().toString())
                .claim("email", "old@esscafe.vn")
                .claim("companyId", UUID.randomUUID().toString())
                .claim("branchRoles", Map.of())
                .expiration(Date.from(Instant.now().plus(Duration.ofMinutes(5))))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
                .compact();

        assertThatThrownBy(() -> jwtService(Duration.ofMinutes(15)).parseAccessToken(unversioned))
                .isInstanceOf(MalformedJwtException.class)
                .hasMessageContaining("auth version");
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
        JwtService otherService = new JwtService(props(Duration.ofMinutes(15),
                new Key("k1", "a-completely-different-secret-key-000000000", true)));
        assertThatThrownBy(() -> otherService.parseAccessToken(token))
                .isInstanceOf(Exception.class);
    }

    // --- key rotation, issuer and audience ---

    @Test
    void issuedTokens_nameTheActiveKey_theIssuer_andTheAudience() {
        JwtService jwt = new JwtService(props(Duration.ofMinutes(15),
                new Key("k1", SECRET, false), new Key("k2", NEXT_SECRET, true)));

        String token = jwt.generateAccessToken(sampleClaims());

        var parsed = Jwts.parser().verifyWith(Keys.hmacShaKeyFor(NEXT_SECRET.getBytes(StandardCharsets.UTF_8)))
                .build().parseSignedClaims(token);
        Claims claims = parsed.getPayload();
        assertThat(parsed.getHeader().getKeyId()).isEqualTo("k2");
        assertThat(claims.getIssuer()).isEqualTo("erp-cafe");
        assertThat(claims.getAudience()).containsExactly("erp-cafe-api");
    }

    @Test
    void aTokenSignedWithARetiredKeyStillConfigured_isAccepted() {
        AccessClaims claims = sampleClaims();
        String beforeRotation = jwtService(Duration.ofMinutes(15)).generateAccessToken(claims);
        JwtService rotated = new JwtService(props(Duration.ofMinutes(15),
                new Key("k2", NEXT_SECRET, true), new Key("k1", SECRET, false)));

        assertThat(rotated.parseAccessToken(beforeRotation))
                .usingRecursiveComparison().ignoringFields("issuedAt").isEqualTo(claims);
    }

    @Test
    void aTokenSignedWithARemovedKey_isRejected() {
        String beforeRotation = jwtService(Duration.ofMinutes(15)).generateAccessToken(sampleClaims());
        JwtService rotated = new JwtService(props(Duration.ofMinutes(15), new Key("k2", NEXT_SECRET, true)));

        assertThatThrownBy(() -> rotated.parseAccessToken(beforeRotation)).isInstanceOf(JwtException.class);
    }

    @Test
    void aTokenWhoseKidNamesAnotherConfiguredKey_isRejected() {
        // signed with k1's secret but claiming k2: the signature is checked with k2's
        String forged = handMade("k2", SECRET, JwtService.ISSUER, JwtService.AUDIENCE);
        JwtService rotated = new JwtService(props(Duration.ofMinutes(15),
                new Key("k2", NEXT_SECRET, true), new Key("k1", SECRET, false)));

        assertThatThrownBy(() -> rotated.parseAccessToken(forged)).isInstanceOf(JwtException.class);
    }

    @Test
    void aTokenWithoutAKeyId_isRejected() {
        // every claim a valid token carries, signed with the configured key: only the kid is missing
        String unnamed = Jwts.builder()
                .issuer(JwtService.ISSUER)
                .audience().add(JwtService.AUDIENCE).and()
                .subject(UUID.randomUUID().toString())
                .claim("email", "user@esscafe.vn")
                .claim("companyId", UUID.randomUUID().toString())
                .claim("branchRoles", Map.of())
                .claim("av", 0)
                .expiration(Date.from(Instant.now().plus(Duration.ofMinutes(5))))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
                .compact();

        assertThatThrownBy(() -> jwtService(Duration.ofMinutes(15)).parseAccessToken(unnamed))
                .isInstanceOf(UnsupportedJwtException.class)
                .hasMessageContaining("names no configured signing key");
    }

    @Test
    void aTokenForAnotherAudience_isRejected() {
        String token = handMade("k1", SECRET, JwtService.ISSUER, "some-other-api");

        assertThatThrownBy(() -> jwtService(Duration.ofMinutes(15)).parseAccessToken(token))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void aTokenFromAnotherIssuer_isRejected() {
        String token = handMade("k1", SECRET, "someone-else", JwtService.AUDIENCE);

        assertThatThrownBy(() -> jwtService(Duration.ofMinutes(15)).parseAccessToken(token))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void theKeySet_needsExactlyOneActiveKey_andDistinctIds() {
        assertThatThrownBy(() -> props(Duration.ofMinutes(15)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("at least one key");
        assertThatThrownBy(() -> props(Duration.ofMinutes(15), new Key("k1", SECRET, false)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("exactly one active");
        assertThatThrownBy(() -> props(Duration.ofMinutes(15),
                new Key("k1", SECRET, true), new Key("k2", NEXT_SECRET, true)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("exactly one active");
        assertThatThrownBy(() -> props(Duration.ofMinutes(15),
                new Key("k1", SECRET, true), new Key("k1", NEXT_SECRET, false)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("k1");
    }
}
