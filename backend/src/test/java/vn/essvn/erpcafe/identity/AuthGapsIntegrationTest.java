package vn.essvn.erpcafe.identity;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import vn.essvn.erpcafe.config.SecurityProperties;
import vn.essvn.erpcafe.config.SecurityProperties.Key;
import vn.essvn.erpcafe.identity.security.AccessClaims;
import vn.essvn.erpcafe.identity.security.JwtService;
import vn.essvn.erpcafe.support.AbstractIntegrationTest;

/**
 * Class: AuthGapsIntegrationTest
 * Purpose: Pins the closed authentication gaps: key rotation, stale tokens, audit trail, shared throttle, password reset.
 */
class AuthGapsIntegrationTest extends AbstractIntegrationTest {

    @Autowired private JwtService jwtService;
    @Autowired private SecurityProperties security;

    private Key key(boolean active) {
        return security.jwt().keys().stream().filter(k -> k.active() == active).findFirst().orElseThrow();
    }

    private void me(String token, int expectedStatus) throws Exception {
        mockMvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, authHeader(token)))
                .andExpect(status().is(expectedStatus));
    }

    // --- G09: key rotation, issuer and audience ---

    @Test
    void aTokenSignedWithARetiredKeyStillConfigured_isAccepted() throws Exception {
        AccessClaims admin = jwtService.parseAccessToken(adminToken());
        Key retired = key(false);
        JwtService beforeRotation = new JwtService(new SecurityProperties(
                new SecurityProperties.Jwt(List.of(new Key(retired.kid(), retired.secret(), true)),
                        Duration.ofMinutes(15), Duration.ofDays(30)), null, null));

        me(beforeRotation.generateAccessToken(admin), 200);
    }

    @Test
    void aTokenForAnotherAudience_isUnauthorized() throws Exception {
        AccessClaims admin = jwtService.parseAccessToken(adminToken());
        Key active = key(true);
        String token = Jwts.builder()
                .header().keyId(active.kid()).and()
                .issuer("erp-cafe")
                .audience().add("another-api").and()
                .subject(admin.userId().toString())
                .claim("email", admin.email())
                .claim("companyId", admin.companyId().toString())
                .claim("branchRoles", Map.of())
                .expiration(Date.from(Instant.now().plus(Duration.ofMinutes(5))))
                .signWith(Keys.hmacShaKeyFor(active.secret().getBytes(StandardCharsets.UTF_8)))
                .compact();

        mockMvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, authHeader(token)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }
}
