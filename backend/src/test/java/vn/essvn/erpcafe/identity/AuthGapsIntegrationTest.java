package vn.essvn.erpcafe.identity;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.jayway.jsonpath.JsonPath;

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

    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final String PASSWORD = "password123";

    @Autowired private JwtService jwtService;
    @Autowired private SecurityProperties security;
    @Autowired @Qualifier("ownerDataSource") private DataSource ownerDataSource;

    private record NewUser(String id, String email) {
    }

    private ResultActions send(MockHttpServletRequestBuilder request, String token, String branchId, String body)
            throws Exception {
        request.header(HttpHeaders.AUTHORIZATION, authHeader(token));
        if (branchId != null) {
            request.header("X-Branch-Id", branchId);
        }
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return mockMvc.perform(request);
    }

    private String json(ResultActions result) throws Exception {
        return result.andExpect(status().is2xxSuccessful()).andReturn().getResponse().getContentAsString();
    }

    private String firstBranch(String token) throws Exception {
        return JsonPath.read(json(send(get("/api/v1/auth/me"), token, null, null)), "$.branchIds[0]");
    }

    /** A branch of the admin's company; the admin is granted OWNER there as its creator. */
    private String createBranch(String admin) throws Exception {
        int n = SEQ.incrementAndGet();
        return JsonPath.read(json(send(post("/api/v1/branches"), admin, null,
                "{\"name\":\"Gaps %d\",\"code\":\"GAP%d\"}".formatted(n, n))), "$.id");
    }

    /** A user of the admin's company holding the role at the branch; the password is password123. */
    private NewUser userWithRole(String admin, String roleName, String branchId) throws Exception {
        String email = "gaps%d@t.test".formatted(SEQ.incrementAndGet());
        String id = JsonPath.read(json(send(post("/api/v1/users"), admin, null,
                "{\"email\":\"%s\",\"password\":\"%s\",\"fullName\":\"Gaps\"}".formatted(email, PASSWORD))),
                "$.id");
        NewUser user = new NewUser(id, email);
        grant(admin, user, branchId, roleName);
        return user;
    }

    private void grant(String admin, NewUser user, String branchId, String roleName) throws Exception {
        String roles = json(send(get("/api/v1/roles"), admin, null, null));
        String roleId = JsonPath.<List<String>>read(roles, "$[?(@.name == '%s')].id".formatted(roleName)).get(0);
        json(send(post("/api/v1/users/" + user.id() + "/access"), admin, null,
                "{\"branchId\":\"%s\",\"roleId\":\"%s\"}".formatted(branchId, roleId)));
    }

    private void expectStale(ResultActions result) throws Exception {
        result.andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

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
                .claim("av", admin.authVersion())
                .expiration(Date.from(Instant.now().plus(Duration.ofMinutes(5))))
                .signWith(Keys.hmacShaKeyFor(active.secret().getBytes(StandardCharsets.UTF_8)))
                .compact();

        mockMvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, authHeader(token)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    // --- G08: tokens end when access changes ---

    @Test
    void afterTheUsersRoleAtABranchChanges_theOldTokenIsUnauthorizedThere() throws Exception {
        String admin = adminToken();
        String branch = firstBranch(admin);
        NewUser user = userWithRole(admin, "MANAGER", branch);
        String old = accessToken(user.email(), PASSWORD);

        grant(admin, user, branch, "CASHIER");

        expectStale(send(get("/api/v1/products"), old, branch, null));
        json(send(get("/api/v1/products"), accessToken(user.email(), PASSWORD), branch, null));
    }

    @Test
    void afterTheAccountIsDisabled_itsTokenIsUnauthorized() throws Exception {
        String admin = adminToken();
        NewUser user = userWithRole(admin, "CASHIER", firstBranch(admin));
        String old = accessToken(user.email(), PASSWORD);

        json(send(put("/api/v1/users/" + user.id() + "/status"), admin, null, "{\"status\":\"DISABLED\"}"));

        expectStale(send(get("/api/v1/auth/me"), old, null, null));
    }

    @Test
    void afterAnAdministrativePasswordReset_theOldTokenIsUnauthorized() throws Exception {
        String admin = adminToken();
        NewUser user = userWithRole(admin, "CASHIER", firstBranch(admin));
        String old = accessToken(user.email(), PASSWORD);

        send(put("/api/v1/users/" + user.id() + "/password"), admin, null, "{\"newPassword\":\"another-pass-1\"}")
                .andExpect(status().isNoContent());

        expectStale(send(get("/api/v1/auth/me"), old, null, null));
        json(send(get("/api/v1/auth/me"), accessToken(user.email(), "another-pass-1"), null, null));
    }

    @Test
    void afterChangingTheirOwnPassword_theOldTokenIsUnauthorized() throws Exception {
        String admin = adminToken();
        NewUser user = userWithRole(admin, "CASHIER", firstBranch(admin));
        String old = accessToken(user.email(), PASSWORD);

        send(post("/api/v1/users/me/password"), old, null,
                "{\"currentPassword\":\"%s\",\"newPassword\":\"another-pass-2\"}".formatted(PASSWORD))
                .andExpect(status().isNoContent());

        expectStale(send(get("/api/v1/auth/me"), old, null, null));
    }

    @Test
    void aBranchClosing_endsTheTokensOfThoseGrantedThere_andNoOneElses() throws Exception {
        String admin = adminToken();
        String deleted = createBranch(admin);
        String deactivated = createBranch(admin);
        NewUser atDeleted = userWithRole(admin, "CASHIER", deleted);
        NewUser atDeactivated = userWithRole(admin, "CASHIER", deactivated);
        NewUser elsewhere = userWithRole(admin, "CASHIER", firstBranch(admin));
        String atDeletedToken = accessToken(atDeleted.email(), PASSWORD);
        String atDeactivatedToken = accessToken(atDeactivated.email(), PASSWORD);
        String elsewhereToken = accessToken(elsewhere.email(), PASSWORD);

        send(delete("/api/v1/branches/" + deleted), admin, null, null).andExpect(status().isNoContent());
        // the owner held the deleted branch too
        expectStale(send(get("/api/v1/auth/me"), admin, null, null));
        admin = adminToken();
        String code = JsonPath.read(json(send(get("/api/v1/branches/" + deactivated), admin, null, null)), "$.code");
        json(send(put("/api/v1/branches/" + deactivated), admin, null,
                "{\"name\":\"Gaps off\",\"code\":\"%s\",\"active\":false}".formatted(code)));

        expectStale(send(get("/api/v1/auth/me"), atDeletedToken, null, null));
        expectStale(send(get("/api/v1/auth/me"), atDeactivatedToken, null, null));
        json(send(get("/api/v1/auth/me"), elsewhereToken, null, null));
        send(delete("/api/v1/branches/" + deactivated), adminToken(), null, null).andExpect(status().isNoContent());
    }

    @Test
    void aTokenNewerThanThisInstancesCopy_isCheckedAgainstTheDatabase() throws Exception {
        String admin = adminToken();
        NewUser user = userWithRole(admin, "CASHIER", firstBranch(admin));
        String old = accessToken(user.email(), PASSWORD);
        json(send(get("/api/v1/auth/me"), old, null, null));

        // another instance changes the user and issues a token; this one still holds the version it read
        new JdbcTemplate(ownerDataSource).update("UPDATE app_user SET auth_version = auth_version + 1 WHERE id = ?",
                UUID.fromString(user.id()));
        AccessClaims claims = jwtService.parseAccessToken(old);
        String newer = jwtService.generateAccessToken(new AccessClaims(claims.userId(), claims.email(),
                claims.companyId(), claims.roleByBranch(), claims.authVersion() + 1));

        json(send(get("/api/v1/auth/me"), newer, null, null));
        expectStale(send(get("/api/v1/auth/me"), old, null, null));
    }
}
