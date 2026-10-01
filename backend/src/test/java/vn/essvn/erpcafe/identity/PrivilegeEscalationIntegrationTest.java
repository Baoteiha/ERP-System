package vn.essvn.erpcafe.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

import vn.essvn.erpcafe.identity.domain.DefaultRole;
import vn.essvn.erpcafe.identity.domain.Permission;
import vn.essvn.erpcafe.identity.domain.Permissions;
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
        User owner = userRepository.findById(ownerId).orElseThrow();
        ownerEmail = owner.getEmail();
        ownerPasswordHash = owner.getPasswordHash();
        ownerStatus = owner.getStatus();
    }

    /** Every other test class logs in as the bootstrap owner, so an attack that ever got through is undone. */
    @AfterEach
    void restoreOwner() {
        User owner = userRepository.findById(ownerId).orElseThrow();
        owner.setEmail(ownerEmail);
        owner.setPasswordHash(ownerPasswordHash);
        owner.setStatus(ownerStatus);
        userRepository.save(owner);
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
        return JsonPath.read(json, "$.id");
    }

    /** Creates a company user through the API, has the owner grant them a role at a branch, and logs in. */
    private Login userWithRole(String roleId) throws Exception {
        int n = SEQ.incrementAndGet();
        String email = "escalation%d@esscafe.vn".formatted(n);
        String json = send(post("/api/v1/users"), adminToken,
                "{\"email\":\"%s\",\"password\":\"password123\",\"fullName\":\"Escalation %d\"}".formatted(email, n))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID userId = UUID.fromString(JsonPath.read(json, "$.id"));
        send(post("/api/v1/users/" + userId + "/access"), adminToken, grant(branchId, roleId))
                .andExpect(status().isOk());
        return new Login(userId, accessToken(email, "password123"));
    }

    private Login manager() throws Exception {
        return userWithRole(roleId(DefaultRole.MANAGER.roleName()));
    }

    private UUID roleAt(UUID userId, UUID branch) {
        return accessRepository.findByUserIdAndBranchId(userId, branch).orElseThrow().getRoleId();
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
        UUID unheld = branchRepository
                .save(new Branch(companyId, "Unheld Branch", "ESCB%d".formatted(SEQ.incrementAndGet())))
                .getId();

        send(post("/api/v1/users/" + manager.userId() + "/access"), manager.token(),
                grant(unheld, roleId(DefaultRole.MANAGER.roleName())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        assertThat(accessRepository.findByUserIdAndBranchId(manager.userId(), unheld)).isEmpty();
    }

    @Test
    void managerResettingTheOwnersPassword_isForbidden() throws Exception {
        Login manager = manager();

        send(put("/api/v1/users/" + ownerId + "/password"), manager.token(), "{\"newPassword\":\"takeover123\"}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        assertThat(userRepository.findById(ownerId).orElseThrow().getPasswordHash()).isEqualTo(ownerPasswordHash);
    }

    @Test
    void managerChangingTheOwnersEmail_isForbidden() throws Exception {
        Login manager = manager();

        send(put("/api/v1/users/" + ownerId), manager.token(),
                "{\"email\":\"takeover%d@esscafe.vn\",\"fullName\":\"Taken Over\"}".formatted(SEQ.incrementAndGet()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        assertThat(userRepository.findById(ownerId).orElseThrow().getEmail()).isEqualTo(ownerEmail);
    }

    @Test
    void managerDisablingTheOwner_isForbidden() throws Exception {
        Login manager = manager();

        send(put("/api/v1/users/" + ownerId + "/status"), manager.token(), "{\"status\":\"DISABLED\"}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        assertThat(userRepository.findById(ownerId).orElseThrow().getStatus()).isEqualTo(UserStatus.ACTIVE);
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
        // The default MANAGER cannot write roles at all; this manager's role adds role:write,
        // so the request gets past the endpoint's authority check and reaches the policy.
        Login manager = userWithRole(createRole(
                Stream.concat(DefaultRole.MANAGER.permissions().stream(), Stream.of(Permissions.ROLE_WRITE))));
        String name = "ESC_WIDE_%d".formatted(SEQ.incrementAndGet());

        send(post("/api/v1/roles"), manager.token(),
                "{\"name\":\"%s\",\"description\":\"d\",\"permissions\":[\"company:write\"]}".formatted(name))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        assertThat(roleRepository.existsByCompanyIdAndName(companyId, name)).isFalse();

        String narrow = createRole(Stream.of(Permissions.SALES_READ));
        send(post("/api/v1/roles/" + narrow + "/permissions"), manager.token(),
                "{\"permissions\":[\"company:write\"]}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        assertThat(roleRepository.findById(UUID.fromString(narrow)).orElseThrow().getPermissions())
                .extracting(Permission::getName)
                .containsExactly(Permissions.SALES_READ);

        send(post("/api/v1/roles"), manager.token(),
                "{\"name\":\"%s\",\"description\":\"d\",\"permissions\":[\"sales:read\"]}".formatted(name))
                .andExpect(status().isCreated());
    }

    @Test
    void managerReshapingARoleWiderThanThemselves_isForbidden() throws Exception {
        Login manager = userWithRole(createRole(
                Stream.concat(DefaultRole.MANAGER.permissions().stream(), Stream.of(Permissions.ROLE_WRITE))));
        // Whoever holds this role can do more than the manager, so the manager may not narrow it either.
        String wide = createRole(Stream.of(Permissions.SALES_READ, Permissions.COMPANY_WRITE));

        send(delete("/api/v1/roles/" + wide + "/permissions/" + Permissions.COMPANY_WRITE), manager.token(), null)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        assertThat(roleRepository.findById(UUID.fromString(wide)).orElseThrow().getPermissions())
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

        send(post("/api/v1/users/" + cashierId + "/access"), manager.token(), grant(branchId, cashierRoleId))
                .andExpect(status().isOk());

        assertThat(roleAt(cashierId, branchId)).hasToString(cashierRoleId);
        // A cashier holds less than the manager, so the manager may also manage the account.
        send(put("/api/v1/users/" + cashierId + "/password"), manager.token(), "{\"newPassword\":\"newpassword1\"}")
                .andExpect(status().isNoContent());
    }
}
