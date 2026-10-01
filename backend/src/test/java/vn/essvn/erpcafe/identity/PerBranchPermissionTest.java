package vn.essvn.erpcafe.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.jayway.jsonpath.JsonPath;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import vn.essvn.erpcafe.catalog.persistence.ProductBranchAvailabilityRepository;
import vn.essvn.erpcafe.common.context.TenantContext;
import vn.essvn.erpcafe.config.SecurityProperties;
import vn.essvn.erpcafe.identity.domain.Permission;
import vn.essvn.erpcafe.identity.persistence.RoleRepository;
import vn.essvn.erpcafe.identity.persistence.UserBranchAccessRepository;
import vn.essvn.erpcafe.identity.persistence.UserRepository;
import vn.essvn.erpcafe.organization.domain.Branch;
import vn.essvn.erpcafe.organization.domain.Company;
import vn.essvn.erpcafe.organization.persistence.BranchRepository;
import vn.essvn.erpcafe.organization.persistence.CompanyRepository;
import vn.essvn.erpcafe.sales.domain.OrderStatus;
import vn.essvn.erpcafe.sales.persistence.OrderRepository;
import vn.essvn.erpcafe.support.AbstractIntegrationTest;

/**
 * Class: PerBranchPermissionTest
 * Purpose: Proves a user's permissions follow the branch they act in, while company-level administration weighs every branch they hold.
 */
class PerBranchPermissionTest extends AbstractIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final String BRANCH = "X-Branch-Id";

    @Autowired
    private OrderRepository orders;
    @Autowired
    private RoleRepository roles;
    @Autowired
    private UserBranchAccessRepository grants;
    @Autowired
    private UserRepository users;
    @Autowired
    private ProductBranchAvailabilityRepository availability;
    @Autowired
    private CompanyRepository companies;
    @Autowired
    private BranchRepository branchRepository;
    @Autowired
    private SecurityProperties security;

    private final List<String> createdBranches = new ArrayList<>();
    private final List<UUID> createdRoles = new ArrayList<>();
    private String admin;
    private String branchA;
    private String branchB;
    /** MANAGER at branch A, CASHIER at branch B. */
    private String user;
    private NewUser userAccount;

    @BeforeEach
    void setUp() throws Exception {
        branchA = createBranch(adminToken());
        branchB = createBranch(adminToken());
        // the owner's grants at the new branches reach a fresh token only
        admin = adminToken();
        userAccount = newUser();
        grant(userAccount, branchA, roleId("MANAGER"));
        grant(userAccount, branchB, roleId("CASHIER"));
        user = accessToken(userAccount.email(), "password123");
    }

    /** Soft-deleting the branches lets the lifecycle listener drop every grant on them, the owner's included. */
    @AfterEach
    void cleanUp() throws Exception {
        String owner = adminToken();
        for (String id : createdBranches) {
            send(delete("/api/v1/branches/" + id), owner, null, null).andExpect(status().isNoContent());
        }
        createdRoles.forEach(roles::deleteById);
    }

    private ResultActions send(MockHttpServletRequestBuilder request, String token, String branchId, String body)
            throws Exception {
        request.header(HttpHeaders.AUTHORIZATION, authHeader(token));
        if (branchId != null) {
            request.header(BRANCH, branchId);
        }
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return mockMvc.perform(request);
    }

    private String json(ResultActions result) throws Exception {
        return result.andExpect(status().is2xxSuccessful()).andReturn().getResponse().getContentAsString();
    }

    private String createBranch(String owner) throws Exception {
        int n = SEQ.incrementAndGet();
        String id = JsonPath.read(json(send(post("/api/v1/branches"), owner, null,
                "{\"name\":\"Per-branch %d\",\"code\":\"PB%d\"}".formatted(n, n))), "$.id");
        createdBranches.add(id);
        return id;
    }

    private record NewUser(String id, String email) {
    }

    /** Creates a user with no grants; the password is password123. */
    private NewUser newUser() throws Exception {
        String email = "perbranch%d@t.test".formatted(SEQ.incrementAndGet());
        String id = JsonPath.read(json(send(post("/api/v1/users"), admin, null,
                "{\"email\":\"%s\",\"password\":\"password123\",\"fullName\":\"Per Branch\"}".formatted(email))),
                "$.id");
        return new NewUser(id, email);
    }

    private void grant(NewUser grantee, String branchId, String roleId) throws Exception {
        json(send(post("/api/v1/users/" + grantee.id() + "/access"), admin, null,
                "{\"branchId\":\"%s\",\"roleId\":\"%s\"}".formatted(branchId, roleId)));
    }

    private String roleId(String name) throws Exception {
        String json = json(send(get("/api/v1/roles"), admin, null, null));
        return JsonPath.<List<String>>read(json, "$[?(@.name == '%s')].id".formatted(name)).get(0);
    }

    private String customRole(String... permissions) throws Exception {
        String names = String.join(",", Arrays.stream(permissions).map(p -> "\"" + p + "\"").toList());
        String id = JsonPath.read(json(send(post("/api/v1/roles"), admin, null,
                "{\"name\":\"PER_BRANCH_%d\",\"description\":\"d\",\"permissions\":[%s]}"
                        .formatted(SEQ.incrementAndGet(), names))), "$.id");
        createdRoles.add(UUID.fromString(id));
        return id;
    }

    /** An active branch of a company of its own. */
    private UUID foreignBranch() {
        int n = SEQ.incrementAndGet();
        Company other = companies.save(new Company("PB Other %d".formatted(n), "PBO%d".formatted(n)));
        return TenantContext.runAs(other.getId(),
                () -> branchRepository.save(new Branch("PB Foreign", "PBF%d".formatted(n))).getId());
    }

    /** A product of the owner's company, priced 20000. */
    private String newProduct(String branchId) throws Exception {
        int n = SEQ.incrementAndGet();
        String categoryId = JsonPath.read(json(send(post("/api/v1/categories"), admin, branchId,
                "{\"name\":\"PB Drinks %d\",\"displayOrder\":1}".formatted(n))), "$.id");
        return JsonPath.read(json(send(post("/api/v1/products"), admin, branchId,
                "{\"categoryId\":\"%s\",\"name\":\"PB Tea %d\",\"sku\":\"PBT-%d\",\"basePrice\":20000}"
                        .formatted(categoryId, n, n))), "$.id");
    }

    /** An order at the branch, paid in full by the owner, so it is COMPLETED and refundable. */
    private String completedOrderAt(String branchId) throws Exception {
        String productId = newProduct(branchId);
        String orderId = JsonPath.read(json(send(post("/api/v1/orders"), admin, branchId,
                "{\"orderType\":\"DINE_IN\",\"lines\":[{\"productId\":\"%s\",\"quantity\":1}]}".formatted(productId))),
                "$.id");
        send(post("/api/v1/orders/" + orderId + "/payments"), admin, branchId, "{\"method\":\"CASH\",\"amount\":20000}")
                .andExpect(jsonPath("$.status").value("COMPLETED"));
        return orderId;
    }

    @Test
    void refund_isWeighedAgainstTheRoleAtTheActiveBranch() throws Exception {
        String atB = completedOrderAt(branchB);
        String atA = completedOrderAt(branchA);

        send(post("/api/v1/orders/" + atB + "/refund"), user, branchB, null)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        assertThat(orders.findById(UUID.fromString(atB)).orElseThrow().getStatus()).isEqualTo(OrderStatus.COMPLETED);

        send(post("/api/v1/orders/" + atA + "/refund"), user, branchA, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REFUNDED"));
    }

    @Test
    void companyLevelAdministration_isWeighedAgainstEveryBranchHeld() throws Exception {
        send(get("/api/v1/users"), user, branchB, null).andExpect(status().isOk());
        send(get("/api/v1/roles"), user, branchB, null).andExpect(status().isOk());
    }

    @Test
    void me_reportsThePermissionsHeldAtEachBranch() throws Exception {
        send(get("/api/v1/auth/me"), user, branchB, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.permissionsByBranch['%s']".formatted(branchA)).value(hasItem("sales:refund")))
                .andExpect(jsonPath("$.permissionsByBranch['%s']".formatted(branchB))
                        .value(not(hasItem("sales:refund"))))
                .andExpect(jsonPath("$.permissionsByBranch['%s']".formatted(branchB)).value(hasItem("sales:write")))
                .andExpect(jsonPath("$.permissions").value(hasItem("sales:refund")));
    }

    @Test
    void outletComparison_showsOnlyBranchesWhereTheUserMayReadReports() throws Exception {
        send(get("/api/v1/reports/outlets"), user, branchB, null)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        String outlets = json(send(get("/api/v1/reports/outlets"), user, null, null));

        assertThat(JsonPath.<List<String>>read(outlets, "$[*].branchId")).containsExactly(branchA);
    }

    @Test
    void roleEdit_appliesToTokensAlreadyIssued() throws Exception {
        NewUser created = newUser();
        String reader = customRole("sales:read");
        grant(created, branchA, reader);
        String token = accessToken(created.email(), "password123");
        send(get("/api/v1/reports/summary"), token, branchA, null).andExpect(status().isForbidden());

        json(send(post("/api/v1/roles/" + reader + "/permissions"), admin, null,
                "{\"permissions\":[\"report:read\"]}"));

        send(get("/api/v1/reports/summary"), token, branchA, null).andExpect(status().isOk());
    }

    private UUID roleAt(NewUser holder, String branchId) {
        return grants.findByUserIdAndBranchId(UUID.fromString(holder.id()), UUID.fromString(branchId))
                .orElseThrow().getRoleId();
    }

    private String passwordHashOf(NewUser account) {
        return TenantContext.runAsSystem(() -> users.findById(UUID.fromString(account.id())).orElseThrow())
                .getPasswordHash();
    }

    private ResultActions resetPassword(NewUser target) throws Exception {
        // acting from A, where the user is a manager: the rules weigh the target's branches, not this one
        return send(put("/api/v1/users/" + target.id() + "/password"), user, branchA,
                "{\"newPassword\":\"another-password-1\"}");
    }

    @Test
    void grantingYourselfMoreAtABranch_isJudgedAtThatBranch() throws Exception {
        send(post("/api/v1/users/" + userAccount.id() + "/access"), user, branchA,
                "{\"branchId\":\"%s\",\"roleId\":\"%s\"}".formatted(branchB, roleId("MANAGER")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        assertThat(roleAt(userAccount, branchB)).isEqualTo(UUID.fromString(roleId("CASHIER")));
    }

    @Test
    void resettingAPeerAtABranch_isAllowed() throws Exception {
        NewUser peer = newUser();
        grant(peer, branchB, roleId("CASHIER"));
        String before = passwordHashOf(peer);

        resetPassword(peer).andExpect(status().isNoContent());

        assertThat(passwordHashOf(peer)).isNotEqualTo(before);
    }

    @Test
    void resettingSomeoneWhoHoldsMoreAtABranch_isForbidden() throws Exception {
        NewUser managerAtB = newUser();
        grant(managerAtB, branchB, roleId("MANAGER"));
        String before = passwordHashOf(managerAtB);

        resetPassword(managerAtB)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        assertThat(passwordHashOf(managerAtB)).isEqualTo(before);
    }

    private ResultActions setAvailability(String token, String productId, String branchId, boolean available)
            throws Exception {
        return send(put("/api/v1/products/" + productId + "/availability"), token, branchA,
                "{\"branchId\":\"%s\",\"available\":%s}".formatted(branchId, available));
    }

    private boolean availableAt(String productId, String branchId) {
        return availability.findByProductIdAndBranchId(UUID.fromString(productId), UUID.fromString(branchId))
                .orElseThrow().isAvailable();
    }

    @Test
    void productAvailability_isSetOnlyWhereTheRoleWritesTheCatalog() throws Exception {
        String productId = newProduct(branchA);
        String unheld = createBranch(admin);
        admin = adminToken();
        setAvailability(admin, productId, branchB, true).andExpect(status().isOk());

        // catalog:write in force at A, where the request acts, but the user is only a cashier at B
        setAvailability(user, productId, branchB, false)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        assertThat(availableAt(productId, branchB)).isTrue();

        setAvailability(user, productId, unheld, false)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        assertThat(availability.findByProductIdAndBranchId(UUID.fromString(productId), UUID.fromString(unheld)))
                .isEmpty();

        UUID foreign = foreignBranch();
        setAvailability(user, productId, foreign.toString(), false)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        assertThat(availability.findByProductIdAndBranchId(UUID.fromString(productId), foreign)).isEmpty();

        setAvailability(user, productId, branchA, false).andExpect(status().isOk());
        assertThat(availableAt(productId, branchA)).isFalse();
    }

    @Test
    void productAvailability_listsOnlyBranchesWhereTheRoleReadsTheCatalog() throws Exception {
        String productId = newProduct(branchA);
        String unheld = createBranch(admin);
        admin = adminToken();
        for (String branchId : List.of(branchA, branchB, unheld)) {
            setAvailability(admin, productId, branchId, true).andExpect(status().isOk());
        }

        String rows = json(send(get("/api/v1/products/" + productId + "/availability"), user, branchA, null));

        assertThat(JsonPath.<List<String>>read(rows, "$[*].branchId")).containsExactlyInAnyOrder(branchA, branchB);
    }

    @Test
    void productPrice_atABranch_needsTheCatalogThere() throws Exception {
        String productId = newProduct(branchA);
        String unheld = createBranch(admin);
        UUID foreign = foreignBranch();
        String price = "/api/v1/products/" + productId + "/price?branchId=";

        send(get(price + branchB), user, branchA, null).andExpect(status().isOk());
        send(get(price + unheld), user, branchA, null)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        send(get(price + foreign), user, branchA, null)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    private record RoleEditor(String token, String tillRoleId) {
    }

    /** Edits roles and refunds at A through a custom role; at B holds only a till role (sell, no refunds). */
    private RoleEditor roleEditor() throws Exception {
        String backOffice = customRole("role:read", "role:write", "sales:read", "sales:write", "sales:refund");
        String till = customRole("sales:read", "sales:write");
        NewUser editor = newUser();
        grant(editor, branchA, backOffice);
        grant(editor, branchB, till);
        return new RoleEditor(accessToken(editor.email(), "password123"), till);
    }

    private Set<String> permissionsOf(String roleId) {
        return TenantContext.runAsSystem(() -> roles.findById(UUID.fromString(roleId)).orElseThrow())
                .getPermissions().stream()
                .map(Permission::getName).collect(Collectors.toSet());
    }

    @Test
    void wideningARoleInUse_isJudgedAtEveryBranchItIsGranted() throws Exception {
        RoleEditor editor = roleEditor();

        send(post("/api/v1/roles/" + editor.tillRoleId() + "/permissions"), editor.token(), branchA,
                "{\"permissions\":[\"sales:refund\"]}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        send(put("/api/v1/roles/" + editor.tillRoleId() + "/permissions"), editor.token(), branchA,
                "{\"permissions\":[\"sales:read\",\"sales:write\",\"sales:refund\"]}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        assertThat(permissionsOf(editor.tillRoleId())).containsExactlyInAnyOrder("sales:read", "sales:write");
    }

    @Test
    void wideningARoleUsedOnlyWhereTheEditorHoldsThePermission_isAllowed() throws Exception {
        RoleEditor editor = roleEditor();
        String counter = customRole("sales:read");
        grant(newUser(), branchA, counter);

        json(send(post("/api/v1/roles/" + counter + "/permissions"), editor.token(), branchB,
                "{\"permissions\":[\"sales:refund\"]}"));

        assertThat(permissionsOf(counter)).containsExactlyInAnyOrder("sales:read", "sales:refund");
    }

    @Test
    void creatingARole_andNarrowingOneInUse_areAllowed() throws Exception {
        RoleEditor editor = roleEditor();

        String created = send(post("/api/v1/roles"), editor.token(), branchB,
                "{\"name\":\"PER_BRANCH_NEW_%d\",\"description\":\"d\",\"permissions\":[\"sales:refund\"]}"
                        .formatted(SEQ.incrementAndGet()))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        createdRoles.add(UUID.fromString(JsonPath.read(created, "$.id")));

        send(delete("/api/v1/roles/" + editor.tillRoleId() + "/permissions/sales:write"), editor.token(), branchB, null)
                .andExpect(status().isOk());
        assertThat(permissionsOf(editor.tillRoleId())).containsExactly("sales:read");
    }

    @Test
    void revokedGrant_stopsWorkingWithTheTokenAlreadyIssued() throws Exception {
        send(delete("/api/v1/users/" + userAccount.id() + "/access/" + branchB), admin, null, null)
                .andExpect(status().isNoContent());

        send(get("/api/v1/auth/me"), user, branchB, null)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        String me = json(send(get("/api/v1/auth/me"), user, null, null));
        assertThat(JsonPath.<List<String>>read(me, "$.branchIds")).containsExactly(branchA);
    }

    @Test
    void roleWidenedAfterItsGrantWasRevoked_grantsNothingThroughTheOldToken() throws Exception {
        String atB = completedOrderAt(branchB);
        // also every till permission, so widening the till role below passes the union check either way
        String backOffice = customRole("role:read", "role:write", "user:read", "user:write", "branch:read",
                "catalog:read", "inventory:read", "sales:read", "sales:write", "sales:refund",
                "staff:read", "staff:clock");
        NewUser actor = newUser();
        grant(actor, branchA, backOffice);
        grant(actor, branchB, roleId("CASHIER"));
        String refreshToken = JsonPath.read(loginBody(actor.email(), "password123"), "$.refreshToken");
        String first = accessToken(actor.email(), "password123");

        // 1. a role with a cashier's permissions, 2. granted to themselves at B in place of CASHIER
        String till = JsonPath.read(json(send(post("/api/v1/roles"), first, branchA,
                ("{\"name\":\"PB_TILL_%d\",\"description\":\"d\",\"permissions\":[\"branch:read\",\"catalog:read\","
                        + "\"inventory:read\",\"sales:read\",\"sales:write\",\"staff:read\",\"staff:clock\"]}")
                        .formatted(SEQ.incrementAndGet()))), "$.id");
        createdRoles.add(UUID.fromString(till));
        json(send(post("/api/v1/users/" + actor.id() + "/access"), first, branchA,
                "{\"branchId\":\"%s\",\"roleId\":\"%s\"}".formatted(branchB, till)));
        // 3. a token naming that role at B
        String old = JsonPath.read(json(send(post("/api/v1/auth/refresh"), first, null,
                "{\"refreshToken\":\"%s\"}".formatted(refreshToken))), "$.accessToken");
        // 4. the grant revoked, so 5. widening the role sees nobody holding it
        send(delete("/api/v1/users/" + actor.id() + "/access/" + branchB), old, branchA, null)
                .andExpect(status().isNoContent());
        json(send(post("/api/v1/roles/" + till + "/permissions"), old, branchA,
                "{\"permissions\":[\"sales:refund\"]}"));

        // 6. the old token still names the role at B
        send(post("/api/v1/orders/" + atB + "/refund"), old, branchB, null)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        assertThat(orders.findById(UUID.fromString(atB)).orElseThrow().getStatus()).isEqualTo(OrderStatus.COMPLETED);
    }

    @Test
    void tokenFromBeforePerBranchPermissions_isUnauthorized() throws Exception {
        String legacy = Jwts.builder()
                .subject(userAccount.id())
                .claim("email", userAccount.email())
                .claim("companyId", branch(branchA).getCompanyId().toString())
                .claim("perms", List.of("sales:refund"))
                .claim("branches", List.of(branchA))
                .expiration(Date.from(Instant.now().plus(Duration.ofMinutes(5))))
                .signWith(Keys.hmacShaKeyFor(security.jwt().secret().getBytes(StandardCharsets.UTF_8)))
                .compact();

        send(get("/api/v1/auth/me"), legacy, null, null)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    /** Reads a branch of any company, as the system. */
    private Branch branch(String branchId) {
        return TenantContext.runAsSystem(() -> branchRepository.findById(UUID.fromString(branchId)).orElseThrow());
    }

    private String branchCode(String branchId) {
        return branch(branchId).getCode();
    }

    @Test
    void branchEdits_needBranchWriteAtTheBranchEdited() throws Exception {
        String keeper = customRole("branch:read", "branch:write");
        NewUser editor = newUser();
        grant(editor, branchA, keeper);
        grant(editor, branchB, roleId("CASHIER"));
        String token = accessToken(editor.email(), "password123");
        String nameOfB = branch(branchB).getName();
        int grantsAtB = grants.findByBranchId(UUID.fromString(branchB)).size();

        send(put("/api/v1/branches/" + branchB), token, branchA,
                "{\"name\":\"Renamed\",\"code\":\"%s\",\"active\":false}".formatted(branchCode(branchB)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        send(delete("/api/v1/branches/" + branchB), token, branchA, null)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        Branch b = branch(branchB);
        assertThat(b.getName()).isEqualTo(nameOfB);
        assertThat(b.isActive()).isTrue();
        assertThat(grants.findByBranchId(UUID.fromString(branchB))).hasSize(grantsAtB);

        send(put("/api/v1/branches/" + branchA), token, branchA,
                "{\"name\":\"Renamed A\",\"code\":\"%s\"}".formatted(branchCode(branchA)))
                .andExpect(status().isOk());
        send(put("/api/v1/branches/" + foreignBranch()), token, branchA,
                "{\"name\":\"x\",\"code\":\"X%d\"}".formatted(SEQ.incrementAndGet()))
                .andExpect(status().isNotFound());
    }
}
