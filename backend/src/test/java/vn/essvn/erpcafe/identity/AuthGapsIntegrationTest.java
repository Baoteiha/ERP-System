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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
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
@ExtendWith(OutputCaptureExtension.class)
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

    private String roleId(String admin, String roleName) throws Exception {
        String roles = json(send(get("/api/v1/roles"), admin, null, null));
        return JsonPath.<List<String>>read(roles, "$[?(@.name == '%s')].id".formatted(roleName)).get(0);
    }

    private void grant(String admin, NewUser user, String branchId, String roleName) throws Exception {
        String roleId = roleId(admin, roleName);
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

    private ResultActions requestReset(String email) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/password-reset/request").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\"}".formatted(email)));
    }

    private ResultActions confirmReset(String token, String newPassword) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/password-reset/confirm").contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"%s\",\"newPassword\":\"%s\"}".formatted(token, newPassword)));
    }

    /** The token of the last reset link mailed to the address, as the logging mail adapter wrote it. */
    private static String mailedToken(CapturedOutput output, String email) {
        Matcher mail = Pattern.compile("Mail to " + Pattern.quote(email) + ".*?token=([0-9a-f]{64})", Pattern.DOTALL)
                .matcher(output.getOut());
        String token = null;
        while (mail.find()) {
            token = mail.group(1);
        }
        assertThat(token).as("a reset link mailed to " + email).isNotNull();
        return token;
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
                        Duration.ofMinutes(15), Duration.ofDays(30)), null, null, null));

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

    @Test
    void aFreshTokenFromAnotherInstance_namingANewRoleOrBranch_isHonoured() throws Exception {
        String admin = adminToken();
        String branch = firstBranch(admin);
        String added = createBranch(admin);
        NewUser user = userWithRole(admin, "MANAGER", branch);
        UUID cashier = UUID.fromString(roleId(admin, "CASHIER"));
        String old = accessToken(user.email(), PASSWORD);
        json(send(get("/api/v1/products"), old, branch, null));

        // another instance moves the user to CASHIER and grants them the new branch, then issues a token;
        // this one still holds the grants it read
        JdbcTemplate db = owner();
        UUID userId = UUID.fromString(user.id());
        db.update("UPDATE user_branch_access SET role_id = ? WHERE user_id = ?", cashier, userId);
        db.update("INSERT INTO user_branch_access (id, version, user_id, branch_id, role_id, created_at, updated_at)"
                + " VALUES (?, 0, ?, ?, ?, now(), now())", UUID.randomUUID(), userId, UUID.fromString(added), cashier);
        AccessClaims claims = jwtService.parseAccessToken(old);
        String fresh = jwtService.generateAccessToken(new AccessClaims(claims.userId(), claims.email(),
                claims.companyId(), Map.of(UUID.fromString(branch), cashier, UUID.fromString(added), cashier),
                claims.authVersion()));

        json(send(get("/api/v1/products"), fresh, branch, null));
        json(send(get("/api/v1/products"), fresh, added, null));
        expectStale(send(get("/api/v1/products"), old, branch, null));
        send(delete("/api/v1/branches/" + added), adminToken(), null, null).andExpect(status().isNoContent());
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
    void aSessionEvent_namesNoActor_whateverBearerTheRequestCarries() throws Exception {
        String admin = adminToken();
        NewUser user = userWithRole(admin, "CASHIER", firstBranch(admin));

        // the user signs in on a client that still sends the owner's access token
        mockMvc.perform(post("/api/v1/auth/login").header(HttpHeaders.AUTHORIZATION, authHeader(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(user.email(), PASSWORD)))
                .andExpect(status().isOk());

        String latest = json(send(get("/api/v1/auth-events?size=1&userId=" + user.id()), admin, null, null));
        assertThat(JsonPath.<String>read(latest, "$.content[0].type")).isEqualTo("LOGIN_SUCCEEDED");
        assertThat(JsonPath.<Map<String, Object>>read(latest, "$.content[0].detail")).doesNotContainKey("actorUserId");
    }

    @Test
    void aBranchClosing_namesWhoClosedIt_onEveryRevokedGrant() throws Exception {
        String admin = adminToken();
        String adminId = JsonPath.read(json(send(get("/api/v1/auth/me"), admin, null, null)), "$.userId");
        String closing = createBranch(admin);
        NewUser user = userWithRole(admin, "CASHIER", closing);

        send(delete("/api/v1/branches/" + closing), admin, null, null).andExpect(status().isNoContent());

        String latest = json(send(get("/api/v1/auth-events?size=1&userId=" + user.id()), adminToken(), null, null));
        assertThat(JsonPath.<String>read(latest, "$.content[0].type")).isEqualTo("ACCESS_REVOKED");
        assertThat(JsonPath.<String>read(latest, "$.content[0].detail.reason")).isEqualTo("BRANCH_CLOSED");
        assertThat(JsonPath.<String>read(latest, "$.content[0].detail.actorUserId")).isEqualTo(adminId);
    }

    @Test
    void aChangeSomeoneMakesToThemselves_namesNoActor() throws Exception {
        String admin = adminToken();
        String adminId = JsonPath.read(json(send(get("/api/v1/auth/me"), admin, null, null)), "$.userId");
        String closing = createBranch(admin);

        // the owner held the branch they close: their own grant goes with it
        send(delete("/api/v1/branches/" + closing), admin, null, null).andExpect(status().isNoContent());

        String trail = json(send(get("/api/v1/auth-events?size=20&userId=" + adminId), adminToken(), null, null));
        List<Map<String, Object>> revoked = JsonPath.read(trail,
                "$.content[?(@.type == 'ACCESS_REVOKED' && @.detail.branchId == '%s')].detail".formatted(closing));
        assertThat(revoked).hasSize(1);
        assertThat(revoked.get(0)).containsEntry("reason", "BRANCH_CLOSED").doesNotContainKey("actorUserId");
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

    // --- G12: password reset by email ---

    @Test
    void aPasswordResetByEmail_setsThePassword_andItsLinkWorksOnce(CapturedOutput output) throws Exception {
        String admin = adminToken();
        NewUser user = userWithRole(admin, "CASHIER", firstBranch(admin));
        String session = loginBody(user.email(), PASSWORD);
        String old = JsonPath.read(session, "$.accessToken");

        requestReset(user.email().toUpperCase()).andExpect(status().isAccepted());
        String token = mailedToken(output, user.email());
        confirmReset(token, "reset-pass-1").andExpect(status().isNoContent());

        assertThat(login(user.email(), "reset-pass-1", uniqueAddress())).isEqualTo(200);
        assertThat(login(user.email(), PASSWORD, uniqueAddress())).isEqualTo(401);
        expectStale(send(get("/api/v1/auth/me"), old, null, null));
        refresh(JsonPath.read(session, "$.refreshToken")).andExpect(status().isUnprocessableEntity());
        confirmReset(token, "reset-pass-2")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("BUSINESS_RULE"));
        assertThat(login(user.email(), "reset-pass-1", uniqueAddress())).isEqualTo(200);
        // stored hashed, for 30 minutes
        Map<String, Object> stored = owner().queryForMap("SELECT token_hash, extract(epoch FROM expires_at - created_at)"
                + " AS ttl FROM password_reset_token WHERE user_id = ?", UUID.fromString(user.id()));
        assertThat(stored.get("token_hash")).isNotEqualTo(token);
        assertThat(((Number) stored.get("ttl")).longValue()).isEqualTo(Duration.ofMinutes(30).toSeconds());
        assertThat(eventTypes(admin, user.id())).contains("PASSWORD_RESET");
    }

    @Test
    void aResetTokenLongerThanAnyIssued_isRefusedAsMalformed() throws Exception {
        confirmReset("f".repeat(65), "reset-pass-9")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION"));
    }

    @Test
    void aResetRequestForAnUnknownEmail_isAnsweredAlike_andMailsNothing(CapturedOutput output) throws Exception {
        String email = "gaps-nobody%d@t.test".formatted(SEQ.incrementAndGet());

        requestReset(email).andExpect(status().isAccepted());

        assertThat(output.getOut()).doesNotContain("Mail to " + email);
    }

    @Test
    void anExpiredResetLink_isRefused_andChangesNothing(CapturedOutput output) throws Exception {
        String admin = adminToken();
        NewUser user = userWithRole(admin, "CASHIER", firstBranch(admin));
        requestReset(user.email()).andExpect(status().isAccepted());
        String token = mailedToken(output, user.email());
        owner().update("UPDATE password_reset_token SET expires_at = now() - interval '1 second' WHERE user_id = ?",
                UUID.fromString(user.id()));

        confirmReset(token, "reset-pass-3")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("BUSINESS_RULE"));

        assertThat(login(user.email(), PASSWORD, uniqueAddress())).isEqualTo(200);
    }

    // --- R44: an open reset link closes when the account's access or email changes ---

    @Test
    void anEmailChange_closesTheOpenResetLinks(CapturedOutput output) throws Exception {
        String admin = adminToken();
        NewUser user = userWithRole(admin, "CASHIER", firstBranch(admin));
        requestReset(user.email()).andExpect(status().isAccepted());
        String token = mailedToken(output, user.email());
        String newEmail = "gaps-moved%d@t.test".formatted(SEQ.incrementAndGet());

        json(send(put("/api/v1/users/" + user.id()), admin, null,
                "{\"email\":\"%s\",\"fullName\":\"Gaps\"}".formatted(newEmail)));

        confirmReset(token, "reset-pass-5")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("BUSINESS_RULE"));
        assertThat(login(newEmail, PASSWORD, uniqueAddress())).isEqualTo(200);
    }

    @Test
    void anAccessChange_closesTheOpenResetLinks(CapturedOutput output) throws Exception {
        String admin = adminToken();
        NewUser user = userWithRole(admin, "CASHIER", firstBranch(admin));
        requestReset(user.email()).andExpect(status().isAccepted());
        String token = mailedToken(output, user.email());

        send(put("/api/v1/users/" + user.id() + "/password"), admin, null, "{\"newPassword\":\"set-by-admin-1\"}")
                .andExpect(status().isNoContent());

        confirmReset(token, "reset-pass-6").andExpect(status().isUnprocessableEntity());
        assertThat(login(user.email(), "set-by-admin-1", uniqueAddress())).isEqualTo(200);
    }

    // --- the reset link's limits and races ---

    private long openLinks(NewUser user) {
        return owner().queryForObject("SELECT count(*) FROM password_reset_token WHERE user_id = ? AND used_at IS NULL",
                Long.class, UUID.fromString(user.id()));
    }

    @Test
    void anAccountGetsAtMostThreeOpenLinks(CapturedOutput output) throws Exception {
        String admin = adminToken();
        NewUser user = userWithRole(admin, "CASHIER", firstBranch(admin));

        for (int i = 0; i < 4; i++) {
            requestReset(user.email()).andExpect(status().isAccepted());
        }

        assertThat(openLinks(user)).isEqualTo(3);
        assertThat(output.getOut().split(Pattern.quote("Mail to " + user.email()), -1)).hasSize(4);
    }

    @Test
    void confirmingOneLink_closesTheAccountsOthers(CapturedOutput output) throws Exception {
        String admin = adminToken();
        NewUser user = userWithRole(admin, "CASHIER", firstBranch(admin));
        requestReset(user.email()).andExpect(status().isAccepted());
        String first = mailedToken(output, user.email());
        requestReset(user.email()).andExpect(status().isAccepted());
        String second = mailedToken(output, user.email());

        confirmReset(second, "reset-pass-7").andExpect(status().isNoContent());

        confirmReset(first, "reset-pass-8").andExpect(status().isUnprocessableEntity());
        assertThat(openLinks(user)).isZero();
        assertThat(login(user.email(), "reset-pass-7", uniqueAddress())).isEqualTo(200);
    }

    @Test
    void aLinkConfirmedForADisabledAccount_isRefused_andStaysOpen(CapturedOutput output) throws Exception {
        String admin = adminToken();
        NewUser user = userWithRole(admin, "CASHIER", firstBranch(admin));
        requestReset(user.email()).andExpect(status().isAccepted());
        String token = mailedToken(output, user.email());
        // disabled behind the app's back: disabling through it would close the link as well
        owner().update("UPDATE app_user SET status = 'DISABLED' WHERE id = ?", UUID.fromString(user.id()));

        confirmReset(token, "reset-pass-10").andExpect(status().isUnprocessableEntity());

        assertThat(openLinks(user)).isEqualTo(1);
        owner().update("UPDATE app_user SET status = 'ACTIVE' WHERE id = ?", UUID.fromString(user.id()));
        assertThat(login(user.email(), PASSWORD, uniqueAddress())).isEqualTo(200);
    }

    @Test
    void twoConfirmationsOfOneLinkAtOnce_exactlyOneSetsThePassword(CapturedOutput output) throws Exception {
        String admin = adminToken();
        NewUser user = userWithRole(admin, "CASHIER", firstBranch(admin));
        requestReset(user.email()).andExpect(status().isAccepted());
        String token = mailedToken(output, user.email());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Integer> statuses;
        try {
            List<Future<Integer>> confirmations = List.of(
                    pool.submit(() -> {
                        start.await(10, TimeUnit.SECONDS);
                        return confirmReset(token, "race-pass-1").andReturn().getResponse().getStatus();
                    }),
                    pool.submit(() -> {
                        start.await(10, TimeUnit.SECONDS);
                        return confirmReset(token, "race-pass-2").andReturn().getResponse().getStatus();
                    }));
            start.countDown();
            statuses = List.of(confirmations.get(0).get(30, TimeUnit.SECONDS),
                    confirmations.get(1).get(30, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }

        assertThat(statuses).containsExactlyInAnyOrder(204, 422);
        String winner = statuses.get(0) == 204 ? "race-pass-1" : "race-pass-2";
        assertThat(login(user.email(), winner, uniqueAddress())).isEqualTo(200);
    }
}

