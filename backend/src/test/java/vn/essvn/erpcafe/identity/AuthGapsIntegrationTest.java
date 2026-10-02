package vn.essvn.erpcafe.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.jayway.jsonpath.JsonPath;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import vn.essvn.erpcafe.config.SecurityProperties;
import vn.essvn.erpcafe.common.context.TenantContext;
import vn.essvn.erpcafe.common.exception.TooManyRequestsException;
import vn.essvn.erpcafe.common.persistence.SystemTransactions;
import vn.essvn.erpcafe.config.SecurityProperties.Key;
import vn.essvn.erpcafe.identity.domain.User;
import vn.essvn.erpcafe.identity.persistence.UserRepository;
import vn.essvn.erpcafe.identity.security.AccessClaims;
import vn.essvn.erpcafe.identity.security.JwtService;
import vn.essvn.erpcafe.identity.security.LoginAttemptService;
import vn.essvn.erpcafe.organization.domain.Company;
import vn.essvn.erpcafe.organization.persistence.CompanyRepository;
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
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository users;
    @Autowired private CompanyRepository companies;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private SystemTransactions system;

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

    private JdbcTemplate owner() {
        return new JdbcTemplate(ownerDataSource);
    }

    private int login(String email, String password, String address) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login")
                        .with(request -> {
                            request.setRemoteAddr(address);
                            return request;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password)))
                .andReturn().getResponse().getStatus();
    }

    private String uniqueAddress() {
        int n = SEQ.incrementAndGet();
        return "10.36.%d.%d".formatted(n / 250, n % 250 + 1);
    }

    private ResultActions refresh(String refreshToken) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"%s\"}".formatted(refreshToken)));
    }

    /** A user of a company of its own, with no grants; the password is password123. */
    private NewUser foreignUser() {
        int n = SEQ.incrementAndGet();
        Company other = TenantContext.runAsSystem(
                () -> companies.save(new Company("Gaps Other %d".formatted(n), "GPO%d".formatted(n))));
        String email = "gaps-foreign%d@t.test".formatted(n);
        User user = TenantContext.runAs(other.getId(),
                () -> users.save(new User(email, passwordEncoder.encode(PASSWORD), "Foreign")));
        return new NewUser(user.getId().toString(), email);
    }

    private List<String> eventTypes(String admin, String userId) throws Exception {
        return JsonPath.read(json(send(get("/api/v1/auth-events?size=100&userId=" + userId), admin, null, null)),
                "$.content[*].type");
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

    // --- R36: a branch lost while it is the active one ---

    @Test
    void aRefusedBranch_isToldApartFromAMissingPermission() throws Exception {
        String admin = adminToken();
        String branch = firstBranch(admin);
        NewUser user = userWithRole(admin, "CASHIER", branch);
        String token = accessToken(user.email(), PASSWORD);

        send(delete("/api/v1/users/" + user.id() + "/access/" + branch), admin, null, null)
                .andExpect(status().isNoContent());

        send(get("/api/v1/products"), token, branch, null)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("BRANCH_FORBIDDEN"));
    }

    @Test
    void theBranchlessAuthEndpoints_ignoreAnActiveBranchNoLongerHeld() throws Exception {
        String admin = adminToken();
        String branch = firstBranch(admin);
        NewUser user = userWithRole(admin, "CASHIER", branch);
        String session = loginBody(user.email(), PASSWORD);
        String token = JsonPath.read(session, "$.accessToken");
        String refresh = JsonPath.read(session, "$.refreshToken");

        send(delete("/api/v1/users/" + user.id() + "/access/" + branch), admin, null, null)
                .andExpect(status().isNoContent());

        send(get("/api/v1/auth/me"), token, branch, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.branchIds").isEmpty());
        String rotated = JsonPath.read(json(send(post("/api/v1/auth/refresh"), token, branch,
                "{\"refreshToken\":\"%s\"}".formatted(refresh))), "$.refreshToken");
        send(post("/api/v1/auth/logout"), token, branch, "{\"refreshToken\":\"%s\"}".formatted(rotated))
                .andExpect(status().isNoContent());
    }

    // --- G14: the authentication audit trail ---

    @Test
    void aLoginFailure_writesAnAuthEvent() throws Exception {
        String admin = adminToken();
        NewUser user = userWithRole(admin, "CASHIER", firstBranch(admin));
        String address = uniqueAddress();

        assertThat(login(user.email(), "not-the-password", address)).isEqualTo(401);

        Map<String, Object> event = owner().queryForMap("SELECT e.company_id, e.ip, e.detail ->> 'email' AS email,"
                + " u.company_id AS user_company FROM auth_event e JOIN app_user u ON u.id = e.user_id"
                + " WHERE e.user_id = ? AND e.type = 'LOGIN_FAILED'", UUID.fromString(user.id()));
        assertThat(event.get("company_id")).isEqualTo(event.get("user_company"));
        assertThat(event).containsEntry("ip", address).containsEntry("email", user.email());
        assertThat(eventTypes(admin, user.id())).contains("LOGIN_FAILED");
    }

    @Test
    void aLoginFailureForAnUnknownEmail_isKeptForNoCompany() throws Exception {
        String email = "gaps-nobody%d@t.test".formatted(SEQ.incrementAndGet());

        assertThat(login(email, "whatever", uniqueAddress())).isEqualTo(401);

        assertThat(owner().queryForObject("SELECT count(*) FROM auth_event WHERE type = 'LOGIN_FAILED'"
                + " AND company_id IS NULL AND user_id IS NULL AND detail ->> 'email' = ?", Long.class, email))
                .isEqualTo(1);
    }

    @Test
    void theAuditTrail_recordsSessionsAndEveryAccessChange_newestFirst() throws Exception {
        String admin = adminToken();
        String branch = firstBranch(admin);
        NewUser user = userWithRole(admin, "CASHIER", branch);
        String first = JsonPath.read(loginBody(user.email(), PASSWORD), "$.refreshToken");
        refresh(first).andExpect(status().isOk());
        refresh(first).andExpect(status().isUnprocessableEntity());
        String session = loginBody(user.email(), PASSWORD);
        mockMvc.perform(post("/api/v1/auth/logout").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"%s\"}".formatted(
                                JsonPath.<String>read(session, "$.refreshToken"))))
                .andExpect(status().isNoContent());
        send(post("/api/v1/users/me/password"), JsonPath.read(session, "$.accessToken"), null,
                "{\"currentPassword\":\"%s\",\"newPassword\":\"another-pass-3\"}".formatted(PASSWORD))
                .andExpect(status().isNoContent());
        send(put("/api/v1/users/" + user.id() + "/password"), admin, null, "{\"newPassword\":\"another-pass-4\"}")
                .andExpect(status().isNoContent());
        json(send(put("/api/v1/users/" + user.id() + "/status"), admin, null, "{\"status\":\"DISABLED\"}"));
        send(delete("/api/v1/users/" + user.id() + "/access/" + branch), admin, null, null)
                .andExpect(status().isNoContent());

        assertThat(eventTypes(admin, user.id())).containsExactly("ACCESS_REVOKED", "STATUS_CHANGED",
                "PASSWORD_RESET", "PASSWORD_CHANGED", "LOGGED_OUT", "LOGIN_SUCCEEDED", "REFRESH_REUSE_DETECTED",
                "TOKEN_REFRESHED", "LOGIN_SUCCEEDED", "ACCESS_GRANTED");
        String adminId = JsonPath.read(json(send(get("/api/v1/auth/me"), admin, null, null)), "$.userId");
        String revoked = json(send(get("/api/v1/auth-events?size=1&userId=" + user.id()), admin, null, null));
        assertThat(JsonPath.<String>read(revoked, "$.content[0].detail.actorUserId")).isEqualTo(adminId);
        assertThat(JsonPath.<String>read(revoked, "$.content[0].detail.branchId")).isEqualTo(branch);
    }

    @Test
    void theAuditTrail_showsOnlyTheCallersCompany() throws Exception {
        NewUser foreign = foreignUser();
        assertThat(login(foreign.email(), PASSWORD, uniqueAddress())).isEqualTo(200);
        String admin = adminToken();

        send(get("/api/v1/auth-events?userId=" + foreign.id()), admin, null, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
        String all = json(send(get("/api/v1/auth-events?size=200"), admin, null, null));
        assertThat(JsonPath.<List<String>>read(all, "$.content[*].userId")).doesNotContain(foreign.id());
        assertThat(owner().queryForObject("SELECT count(*) FROM auth_event WHERE user_id = ?", Long.class,
                UUID.fromString(foreign.id()))).isEqualTo(1);
    }

    @Test
    void theAuditTrail_needsUserRead() throws Exception {
        String admin = adminToken();
        NewUser cashier = userWithRole(admin, "CASHIER", firstBranch(admin));

        send(get("/api/v1/auth-events"), accessToken(cashier.email(), PASSWORD), null, null)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void theAppRole_addsToTheAuditTrail_butNeverRewritesIt() {
        assertThat(jdbc.queryForObject("SELECT has_table_privilege('auth_event', 'SELECT')", Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("SELECT has_table_privilege('auth_event', 'INSERT')", Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("SELECT has_table_privilege('auth_event', 'UPDATE, DELETE')", Boolean.class))
                .isFalse();
    }

    // --- G18: one login throttle for every instance ---

    @Test
    void throttleCounts_surviveANewInstance_asReplicasShareThem() {
        String email = "gaps-throttle%d@t.test".formatted(SEQ.incrementAndGet());
        String address = uniqueAddress();
        LoginAttemptService one = new LoginAttemptService(jdbc, system, security);
        for (int i = 0; i < security.login().maxFailures(); i++) {
            one.recordFailure(email, address);
        }

        LoginAttemptService another = new LoginAttemptService(jdbc, system, security);
        assertThatThrownBy(() -> another.checkNotBlocked(email, address)).isInstanceOf(TooManyRequestsException.class);
        another.reset(email, address);
        assertThatCode(() -> one.checkNotBlocked(email, address)).doesNotThrowAnyException();
    }
}
