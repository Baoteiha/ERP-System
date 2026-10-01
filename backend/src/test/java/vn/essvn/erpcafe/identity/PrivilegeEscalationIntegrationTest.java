package vn.essvn.erpcafe.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.jayway.jsonpath.JsonPath;

import vn.essvn.erpcafe.common.context.TenantContext;
import vn.essvn.erpcafe.identity.domain.DefaultRole;
import vn.essvn.erpcafe.identity.domain.Permission;
import vn.essvn.erpcafe.identity.domain.Permissions;
import vn.essvn.erpcafe.identity.domain.Role;
import vn.essvn.erpcafe.identity.domain.User;
import vn.essvn.erpcafe.identity.domain.UserStatus;
import vn.essvn.erpcafe.identity.persistence.RoleRepository;
import vn.essvn.erpcafe.identity.persistence.UserBranchAccessRepository;
import vn.essvn.erpcafe.identity.persistence.UserRepository;
import vn.essvn.erpcafe.organization.domain.Branch;
import vn.essvn.erpcafe.organization.persistence.BranchRepository;
import vn.essvn.erpcafe.support.AbstractIntegrationTest;

/**
 * Class: PrivilegeEscalationIntegrationTest
 * Purpose: Proves a manager may grant, manage and edit roles only within their own permissions, and refusals change nothing.
 */
class PrivilegeEscalationIntegrationTest extends AbstractIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired private UserRepository userRepository;
    @Autowired private UserBranchAccessRepository accessRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private BranchRepository branchRepository;

    private String adminToken;
    private UUID ownerId;
    private UUID companyId;
    private UUID branchId;
    private String ownerEmail;
    private String ownerPasswordHash;
    private UserStatus ownerStatus;
    private final List<UUID> createdUsers = new ArrayList<>();
    private final List<UUID> createdRoles = new ArrayList<>();
    private final List<UUID> createdBranches = new ArrayList<>();

    private record Login(UUID userId, String token) {
    }

    @BeforeEach
    void setUp() throws Exception {
        adminToken = adminToken();
        String me = send(get("/api/v1/auth/me"), adminToken, null)
                .andReturn().getResponse().getContentAsString();
        ownerId = UUID.fromString(JsonPath.read(me, "$.userId"));
        companyId = UUID.fromString(JsonPath.read(me, "$.companyId"));
        branchId = UUID.fromString(JsonPath.<List<String>>read(me, "$.branchIds").get(0));
        User owner = user(ownerId);
        ownerEmail = owner.getEmail();
        ownerPasswordHash = owner.getPasswordHash();
        ownerStatus = owner.getStatus();
    }

    /**
     * Other test classes log in as the bootstrap owner and read the seeded company, so an attack that
     * ever got through is undone, and the accounts, roles and branches made here are retired.
     */
    @AfterEach
    void cleanUp() {
        TenantContext.runAsSystem(() -> {
            User owner = userRepository.findById(ownerId).orElseThrow();
            owner.setEmail(ownerEmail);
            owner.setPasswordHash(ownerPasswordHash);
            owner.setStatus(ownerStatus);
            userRepository.save(owner);
            for (UUID userId : createdUsers) {
                accessRepository.deleteAll(accessRepository.findByUserId(userId));
                User user = userRepository.findById(userId).orElseThrow();
                user.setStatus(UserStatus.DISABLED);
                userRepository.save(user);
            }
            createdRoles.forEach(roleRepository::deleteById);
            createdBranches.forEach(id -> branchRepository.delete(branchRepository.findById(id).orElseThrow()));
        });
    }

    private ResultActions send(MockHttpServletRequestBuilder request, String token, String body) throws Exception {
        request.header(HttpHeaders.AUTHORIZATION, authHeader(token));
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return mockMvc.perform(request);
    }

    private static String grant(UUID branch, String roleId) {
        return "{\"branchId\":\"%s\",\"roleId\":\"%s\"}".formatted(branch, roleId);
    }

    private String roleId(String name) throws Exception {
        String json = send(get("/api/v1/roles"), adminToken, null).andReturn().getResponse().getContentAsString();
        return JsonPath.<List<String>>read(json, "$[?(@.name == '%s')].id".formatted(name)).get(0);
    }

    /** Creates a company role through the API as the owner. */
    private String createRole(Stream<String> permissions) throws Exception {
        String names = permissions.map(p -> "\"" + p + "\"").collect(Collectors.joining(","));
        String json = send(post("/api/v1/roles"), adminToken,
                "{\"name\":\"ESC_ROLE_%d\",\"description\":\"d\",\"permissions\":[%s]}"
                        .formatted(SEQ.incrementAndGet(), names))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(json, "$.id");
        createdRoles.add(UUID.fromString(id));
        return id;
    }

    /** A manager whose role also carries role:write, which the default MANAGER lacks entirely. */
    private Login roleWritingManager() throws Exception {
        return userWithRole(createRole(
                Stream.concat(DefaultRole.MANAGER.permissions().stream(), Stream.of(Permissions.ROLE_WRITE))));
    }

    /** A second branch of the seeded company, held by nobody until a test grants it. */
    private UUID newBranch() {
        UUID id = TenantContext.runAs(companyId, () -> branchRepository
                .save(new Branch("Unheld Branch", "ESCB%d".formatted(SEQ.incrementAndGet())))
                .getId());
        createdBranches.add(id);
        return id;
    }

    private Login userWithRole(String roleId) throws Exception {
        return userWithRole(roleId, List.of(branchId));
    }

    /** Creates a company user through the API, has the owner grant them a role at each branch, and logs in. */
    private Login userWithRole(String roleId, List<UUID> branches) throws Exception {
        int n = SEQ.incrementAndGet();
        String email = "escalation%d@esscafe.vn".formatted(n);
        String json = send(post("/api/v1/users"), adminToken,
                "{\"email\":\"%s\",\"password\":\"password123\",\"fullName\":\"Escalation %d\"}".formatted(email, n))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID userId = UUID.fromString(JsonPath.read(json, "$.id"));
        createdUsers.add(userId);
        for (UUID branch : branches) {
            send(post("/api/v1/users/" + userId + "/access"), adminToken, grant(branch, roleId))
                    .andExpect(status().isOk());
        }
        return new Login(userId, accessToken(email, "password123"));
    }

    private Login manager() throws Exception {
        return userWithRole(roleId(DefaultRole.MANAGER.roleName()));
    }

    /** A cashier holds less than a manager, but this one works only at a branch the manager does not hold. */
    private Login cashierAtAnotherBranch() throws Exception {
        return userWithRole(roleId(DefaultRole.CASHIER.roleName()), List.of(newBranch()));
    }

    /** Holds every permission OWNER does, at the owner's branch, without being an owner. */
    private Login nonOwnerWithEveryPermission() throws Exception {
        return userWithRole(createRole(Permissions.ALL.stream()));
    }

    private UUID roleAt(UUID userId, UUID branch) {
        return TenantContext.runAsSystem(() -> accessRepository.findByUserIdAndBranchId(userId, branch))
                .orElseThrow().getRoleId();
    }

    /** Reads a user of the seeded company, as that company. */
    private User user(UUID id) {
        return TenantContext.runAs(companyId, () -> userRepository.findById(id).orElseThrow());
    }

    /** Reads a role of the seeded company, as that company. */
    private Role role(String id) {
        return TenantContext.runAs(companyId, () -> roleRepository.findById(UUID.fromString(id)).orElseThrow());
    }

    private String passwordHashOf(UUID userId) {
        return user(userId).getPasswordHash();
    }

    @Test
    void managerGrantingThemselvesOwner_isForbidden() throws Exception {
        Login manager = manager();
        String managerRoleId = roleId(DefaultRole.MANAGER.roleName());

        send(post("/api/v1/users/" + manager.userId() + "/access"), manager.token(),
                grant(branchId, roleId(DefaultRole.OWNER.roleName())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        assertThat(roleAt(manager.userId(), branchId)).hasToString(managerRoleId);
    }

    @Test
    void managerGrantingThemselvesABranchTheyDoNotHold_isForbidden() throws Exception {
        Login manager = manager();
        UUID unheld = newBranch();

        send(post("/api/v1/users/" + manager.userId() + "/access"), manager.token(),
                grant(unheld, roleId(DefaultRole.MANAGER.roleName())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        assertThat(TenantContext.runAsSystem(() -> accessRepository.findByUserIdAndBranchId(manager.userId(), unheld)))
                .isEmpty();
    }

    @Test
    void managerResettingTheOwnersPassword_isForbidden() throws Exception {
        Login manager = manager();

        send(put("/api/v1/users/" + ownerId + "/password"), manager.token(), "{\"newPassword\":\"takeover123\"}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        assertThat(user(ownerId).getPasswordHash()).isEqualTo(ownerPasswordHash);
    }

    @Test
    void managerChangingTheOwnersEmail_isForbidden() throws Exception {
        Login manager = manager();

        send(put("/api/v1/users/" + ownerId), manager.token(),
                "{\"email\":\"takeover%d@esscafe.vn\",\"fullName\":\"Taken Over\"}".formatted(SEQ.incrementAndGet()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        assertThat(user(ownerId).getEmail()).isEqualTo(ownerEmail);
    }

    @Test
    void managerDisablingTheOwner_isForbidden() throws Exception {
        Login manager = manager();

        send(put("/api/v1/users/" + ownerId + "/status"), manager.token(), "{\"status\":\"DISABLED\"}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        assertThat(user(ownerId).getStatus()).isEqualTo(UserStatus.ACTIVE);
    }

    @Test
    void managerDemotingOrRevokingAnOwner_isForbidden() throws Exception {
        Login manager = manager();
        // A second owner, so the bootstrap owner's own grants are never at stake.
        String ownerRoleId = roleId(DefaultRole.OWNER.roleName());
        Login otherOwner = userWithRole(ownerRoleId);

        send(post("/api/v1/users/" + otherOwner.userId() + "/access"), manager.token(),
                grant(branchId, roleId(DefaultRole.CASHIER.roleName())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        send(delete("/api/v1/users/" + otherOwner.userId() + "/access/" + branchId), manager.token(), null)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        assertThat(roleAt(otherOwner.userId(), branchId)).hasToString(ownerRoleId);
    }

    @Test
    void managerCreatingOrWideningARoleBeyondTheirPermissions_isForbidden() throws Exception {
        // role:write gets the request past the endpoint's authority check, so the policy decides.
        Login manager = roleWritingManager();
        String name = "ESC_WIDE_%d".formatted(SEQ.incrementAndGet());

        send(post("/api/v1/roles"), manager.token(),
                "{\"name\":\"%s\",\"description\":\"d\",\"permissions\":[\"company:write\"]}".formatted(name))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        assertThat(TenantContext.runAs(companyId, () -> roleRepository.existsByName(name))).isFalse();

        String narrow = createRole(Stream.of(Permissions.SALES_READ));
        send(post("/api/v1/roles/" + narrow + "/permissions"), manager.token(),
                "{\"permissions\":[\"company:write\"]}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        assertThat(role(narrow).getPermissions())
                .extracting(Permission::getName)
                .containsExactly(Permissions.SALES_READ);

        String created = send(post("/api/v1/roles"), manager.token(),
                "{\"name\":\"%s\",\"description\":\"d\",\"permissions\":[\"sales:read\"]}".formatted(name))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        createdRoles.add(UUID.fromString(JsonPath.read(created, "$.id")));
    }

    @Test
    void managerReplacingARolesPermissionsBeyondTheirOwn_isForbidden() throws Exception {
        Login manager = roleWritingManager();
        String narrow = createRole(Stream.of(Permissions.SALES_READ));

        send(put("/api/v1/roles/" + narrow + "/permissions"), manager.token(),
                "{\"permissions\":[\"sales:read\",\"company:write\"]}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        assertThat(role(narrow).getPermissions())
                .extracting(Permission::getName)
                .containsExactly(Permissions.SALES_READ);
    }

    @Test
    void managerReshapingARoleWiderThanThemselves_isForbidden() throws Exception {
        Login manager = roleWritingManager();
        // Whoever holds this role can do more than the manager, so the manager may not narrow it either.
        String wide = createRole(Stream.of(Permissions.SALES_READ, Permissions.COMPANY_WRITE));

        send(delete("/api/v1/roles/" + wide + "/permissions/" + Permissions.COMPANY_WRITE), manager.token(), null)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        assertThat(role(wide).getPermissions())
                .extracting(Permission::getName)
                .containsExactlyInAnyOrder(Permissions.SALES_READ, Permissions.COMPANY_WRITE);
    }

    @Test
    void managerGrantingCashierAtTheirOwnBranch_isAllowed() throws Exception {
        Login manager = manager();
        String cashierRoleId = roleId(DefaultRole.CASHIER.roleName());
        int n = SEQ.incrementAndGet();
        String json = send(post("/api/v1/users"), manager.token(),
                "{\"email\":\"esc-cashier%d@esscafe.vn\",\"password\":\"password123\",\"fullName\":\"Cashier\"}"
                        .formatted(n))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID cashierId = UUID.fromString(JsonPath.read(json, "$.id"));
        createdUsers.add(cashierId);

        send(post("/api/v1/users/" + cashierId + "/access"), manager.token(), grant(branchId, cashierRoleId))
                .andExpect(status().isOk());

        assertThat(roleAt(cashierId, branchId)).hasToString(cashierRoleId);
        // A cashier holds less than the manager, so the manager may also manage the account.
        send(put("/api/v1/users/" + cashierId + "/password"), manager.token(), "{\"newPassword\":\"newpassword1\"}")
                .andExpect(status().isNoContent());
    }

    @Test
    void managerResettingThePasswordOfAUserAtAnotherBranch_isForbidden() throws Exception {
        Login manager = manager();
        Login cashier = cashierAtAnotherBranch();
        String hash = passwordHashOf(cashier.userId());

        send(put("/api/v1/users/" + cashier.userId() + "/password"), manager.token(), "{\"newPassword\":\"takeover123\"}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        assertThat(passwordHashOf(cashier.userId())).isEqualTo(hash);
    }

    @Test
    void managerChangingTheEmailOfAUserAtAnotherBranch_isForbidden() throws Exception {
        Login manager = manager();
        Login cashier = cashierAtAnotherBranch();
        String email = user(cashier.userId()).getEmail();

        send(put("/api/v1/users/" + cashier.userId()), manager.token(),
                "{\"email\":\"takeover%d@esscafe.vn\",\"fullName\":\"Taken Over\"}".formatted(SEQ.incrementAndGet()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        assertThat(user(cashier.userId()).getEmail()).isEqualTo(email);
    }

    @Test
    void managerDisablingAUserAtAnotherBranch_isForbidden() throws Exception {
        Login manager = manager();
        Login cashier = cashierAtAnotherBranch();

        send(put("/api/v1/users/" + cashier.userId() + "/status"), manager.token(), "{\"status\":\"DISABLED\"}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        assertThat(user(cashier.userId()).getStatus()).isEqualTo(UserStatus.ACTIVE);
    }

    @Test
    void managerRevokingAGrantAtABranchTheyDoNotHold_isForbidden() throws Exception {
        Login manager = manager();
        UUID elsewhere = newBranch();
        String cashierRoleId = roleId(DefaultRole.CASHIER.roleName());
        // Works at the manager's branch and at one the manager does not hold.
        Login cashier = userWithRole(cashierRoleId, List.of(branchId, elsewhere));

        send(delete("/api/v1/users/" + cashier.userId() + "/access/" + elsewhere), manager.token(), null)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        assertThat(roleAt(cashier.userId(), elsewhere)).hasToString(cashierRoleId);
    }

    @Test
    void nonOwnerWithEveryPermission_grantingThemselvesOwner_isForbidden() throws Exception {
        Login almostOwner = nonOwnerWithEveryPermission();
        UUID ownRole = roleAt(almostOwner.userId(), branchId);

        send(post("/api/v1/users/" + almostOwner.userId() + "/access"), almostOwner.token(),
                grant(branchId, roleId(DefaultRole.OWNER.roleName())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        assertThat(roleAt(almostOwner.userId(), branchId)).isEqualTo(ownRole);
    }

    @Test
    void nonOwnerWithEveryPermission_resettingAnOwnersPassword_isForbidden() throws Exception {
        Login almostOwner = nonOwnerWithEveryPermission();
        // A second owner at the same branch only, so nothing but the OWNER rule stands in the way.
        Login otherOwner = userWithRole(roleId(DefaultRole.OWNER.roleName()));
        String hash = passwordHashOf(otherOwner.userId());

        send(put("/api/v1/users/" + otherOwner.userId() + "/password"), almostOwner.token(),
                "{\"newPassword\":\"takeover123\"}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        assertThat(passwordHashOf(otherOwner.userId())).isEqualTo(hash);
    }

    @Test
    void nonOwnerWithEveryPermission_revokingAnOwner_isForbidden() throws Exception {
        Login almostOwner = nonOwnerWithEveryPermission();
        String ownerRoleId = roleId(DefaultRole.OWNER.roleName());
        Login otherOwner = userWithRole(ownerRoleId);

        send(delete("/api/v1/users/" + otherOwner.userId() + "/access/" + branchId), almostOwner.token(), null)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        assertThat(roleAt(otherOwner.userId(), branchId)).hasToString(ownerRoleId);
    }
}
