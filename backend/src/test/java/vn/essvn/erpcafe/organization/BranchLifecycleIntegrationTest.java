package vn.essvn.erpcafe.organization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import com.jayway.jsonpath.JsonPath;

import vn.essvn.erpcafe.common.context.TenantContext;
import vn.essvn.erpcafe.identity.domain.UserBranchAccess;
import vn.essvn.erpcafe.identity.persistence.RoleRepository;
import vn.essvn.erpcafe.identity.persistence.UserBranchAccessRepository;
import vn.essvn.erpcafe.organization.domain.Branch;
import vn.essvn.erpcafe.organization.persistence.BranchRepository;
import vn.essvn.erpcafe.sales.domain.Order;
import vn.essvn.erpcafe.sales.domain.OrderType;
import vn.essvn.erpcafe.sales.persistence.OrderRepository;
import vn.essvn.erpcafe.support.AbstractIntegrationTest;

/**
 * Class: BranchLifecycleIntegrationTest
 * Purpose: Proves branch creation, deactivation and deletion keep user access, the delete guards and company creation consistent.
 */
class BranchLifecycleIntegrationTest extends AbstractIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired
    private BranchRepository branches;
    @Autowired
    private UserBranchAccessRepository grants;
    @Autowired
    private RoleRepository roles;
    @Autowired
    private OrderRepository orders;

    private final List<UUID> createdBranches = new ArrayList<>();
    private final List<Order> createdOrders = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        TenantContext.runAsSystem(() -> {
            orders.deleteAll(createdOrders);
            for (UUID id : createdBranches) {
                grants.deleteAll(grants.findByBranchId(id));
                branches.findById(id).ifPresent(branches::delete);
            }
        });
    }

    /** Reads a branch of any company, as the system. */
    private Branch branch(String id) {
        return TenantContext.runAsSystem(() -> branches.findById(UUID.fromString(id)).orElseThrow());
    }

    private String postJson(String token, String url, String body) throws Exception {
        return mockMvc.perform(post(url)
                        .header(HttpHeaders.AUTHORIZATION, authHeader(token))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn().getResponse().getContentAsString();
    }

    private List<String> meBranches(String token) throws Exception {
        return JsonPath.read(mockMvc.perform(get("/api/v1/auth/me")
                .header(HttpHeaders.AUTHORIZATION, authHeader(token)))
                .andReturn().getResponse().getContentAsString(), "$.branchIds");
    }

    private String createBranch(String token) throws Exception {
        int n = SEQ.incrementAndGet();
        String body = postJson(token, "/api/v1/branches",
                "{\"name\":\"Lifecycle %d\",\"code\":\"LC%d\"}".formatted(n, n));
        String id = JsonPath.read(body, "$.id");
        createdBranches.add(UUID.fromString(id));
        return id;
    }

    /** A fresh user holding {@code roleName} at each given branch, with its login credentials. */
    private String userWithRole(String adminToken, String roleName, String... branchIds) throws Exception {
        int n = SEQ.incrementAndGet();
        String email = "lifecycle%d@t.test".formatted(n);
        String userId = JsonPath.read(postJson(adminToken, "/api/v1/users",
                "{\"email\":\"%s\",\"password\":\"password123\",\"fullName\":\"LC\"}".formatted(email)), "$.id");
        UUID companyId = branch(branchIds[0]).getCompanyId();
        UUID roleId = TenantContext.runAs(companyId, () -> roles.findByName(roleName)).orElseThrow().getId();
        for (String branchId : branchIds) {
            mockMvc.perform(post("/api/v1/users/" + userId + "/access")
                            .header(HttpHeaders.AUTHORIZATION, authHeader(adminToken))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"branchId\":\"%s\",\"roleId\":\"%s\"}".formatted(branchId, roleId)))
                    .andExpect(status().isOk());
        }
        return userId + "|" + email;
    }

    private String seededBranch(String adminToken) throws Exception {
        return meBranches(adminToken).get(0);
    }

    @Test
    void ownerCreatesBranch_ownerSeesItInNextMe() throws Exception {
        String branchId = createBranch(adminToken());

        assertThat(meBranches(adminToken())).contains(branchId);
    }

    @Test
    void deleteBranch_userWhoHadItLosesIt() throws Exception {
        String admin = adminToken();
        String branchId = createBranch(admin);
        String email = userWithRole(admin, "CASHIER", branchId).split("\\|")[1];
        assertThat(meBranches(accessToken(email, "password123"))).contains(branchId);

        mockMvc.perform(delete("/api/v1/branches/" + branchId)
                .header(HttpHeaders.AUTHORIZATION, authHeader(admin))).andExpect(status().isNoContent());

        String fresh = accessToken(email, "password123");
        assertThat(meBranches(fresh)).doesNotContain(branchId);
        assertThat(meBranches(adminToken())).doesNotContain(branchId);
        mockMvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, authHeader(fresh))
                .header("X-Branch-Id", branchId)).andExpect(status().isForbidden());
    }

    @Test
    void deactivateBranch_userWhoHadItLosesIt() throws Exception {
        String admin = adminToken();
        String branchId = createBranch(admin);
        String email = userWithRole(admin, "CASHIER", branchId).split("\\|")[1];

        mockMvc.perform(put("/api/v1/branches/" + branchId)
                        .header(HttpHeaders.AUTHORIZATION, authHeader(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Lifecycle off\",\"code\":\"%s\",\"active\":false}"
                                .formatted(branch(branchId).getCode())))
                .andExpect(status().isOk());

        String fresh = accessToken(email, "password123");
        assertThat(meBranches(fresh)).doesNotContain(branchId);
        mockMvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, authHeader(fresh))
                .header("X-Branch-Id", branchId)).andExpect(status().isForbidden());
    }

    @Test
    void deleteBranch_withOpenOrder_isRefused() throws Exception {
        String admin = adminToken();
        String branchId = createBranch(admin);
        createdOrders.add(TenantContext.runAsSystem(() -> orders.save(
                new Order(UUID.fromString(branchId), null, OrderType.DINE_IN, BigDecimal.ZERO))));

        mockMvc.perform(delete("/api/v1/branches/" + branchId)
                        .header(HttpHeaders.AUTHORIZATION, authHeader(admin)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));

        assertThat(TenantContext.runAsSystem(() -> branches.findById(UUID.fromString(branchId)))).isPresent();
    }

    @Test
    void managerCanManageUser_afterUsersOtherBranchWasDeleted() throws Exception {
        String admin = adminToken();
        String home = seededBranch(admin);
        String extra = createBranch(admin);
        String managerEmail = userWithRole(admin, "MANAGER", home).split("\\|")[1];
        String[] user = userWithRole(admin, "CASHIER", home, extra).split("\\|");
        String manager = accessToken(managerEmail, "password123");
        String body = "{\"email\":\"%s\",\"fullName\":\"Renamed\"}".formatted(user[1]);

        mockMvc.perform(put("/api/v1/users/" + user[0]).header(HttpHeaders.AUTHORIZATION, authHeader(manager))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());

        mockMvc.perform(delete("/api/v1/branches/" + extra)
                .header(HttpHeaders.AUTHORIZATION, authHeader(admin))).andExpect(status().isNoContent());

        mockMvc.perform(put("/api/v1/users/" + user[0]).header(HttpHeaders.AUTHORIZATION, authHeader(manager))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());
    }

    @Test
    void tenantCannotCreateCompany() throws Exception {
        mockMvc.perform(post("/api/v1/companies")
                        .header(HttpHeaders.AUTHORIZATION, authHeader(adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Another\",\"code\":\"ANO%d\"}".formatted(SEQ.incrementAndGet())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void deactivateBranch_withOpenOrder_isRefusedAndKeepsGrants() throws Exception {
        String admin = adminToken();
        String branchId = createBranch(admin);
        createdOrders.add(TenantContext.runAsSystem(() -> orders.save(
                new Order(UUID.fromString(branchId), null, OrderType.DINE_IN, BigDecimal.ZERO))));
        Branch branch = branch(branchId);

        mockMvc.perform(put("/api/v1/branches/" + branchId)
                        .header(HttpHeaders.AUTHORIZATION, authHeader(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"x\",\"code\":\"%s\",\"active\":false}".formatted(branch.getCode())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));

        assertThat(branch(branchId).isActive()).isTrue();
        assertThat(TenantContext.runAsSystem(() -> grants.findByBranchId(UUID.fromString(branchId)))).isNotEmpty();
    }

    @Test
    void grantOnDeletedBranch_givesNeitherPermissionsNorOwnership() throws Exception {
        String admin = adminToken();
        String home = seededBranch(admin);
        String dead = createBranch(admin);
        String[] user = userWithRole(admin, "CASHIER", home).split("\\|");
        UUID companyId = branch(home).getCompanyId();
        // a leftover from before grants were dropped on delete: seeded straight into the table
        TenantContext.runAs(companyId, () -> grants.save(new UserBranchAccess(UUID.fromString(user[0]),
                UUID.fromString(dead), roles.findByName("OWNER").orElseThrow().getId())));
        TenantContext.runAsSystem(() -> branches.delete(branch(dead)));

        String token = accessToken(user[1], "password123");

        mockMvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, authHeader(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.permissions").value(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.hasItem("sales:refund"))))
                .andExpect(jsonPath("$.roles").value(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.hasItem("OWNER"))));
    }

    @Test
    void nonOwnerCreatesBranch_getsOwnRoleThere_orIsRefusedWithoutHeader() throws Exception {
        String admin = adminToken();
        String home = seededBranch(admin);
        int n = SEQ.incrementAndGet();
        String roleId = JsonPath.read(postJson(admin, "/api/v1/roles",
                "{\"name\":\"BranchMaker%d\",\"description\":\"d\",\"permissions\":[\"branch:read\",\"branch:write\"]}"
                        .formatted(n)), "$.id");
        String userId = JsonPath.read(postJson(admin, "/api/v1/users",
                "{\"email\":\"maker%d@t.test\",\"password\":\"password123\",\"fullName\":\"M\"}".formatted(n)), "$.id");
        mockMvc.perform(post("/api/v1/users/" + userId + "/access")
                .header(HttpHeaders.AUTHORIZATION, authHeader(admin)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"branchId\":\"%s\",\"roleId\":\"%s\"}".formatted(home, roleId)))
                .andExpect(status().isOk());
        String token = accessToken("maker%d@t.test".formatted(n), "password123");
        long before = TenantContext.runAsSystem(() -> branches.count());

        mockMvc.perform(post("/api/v1/branches").header(HttpHeaders.AUTHORIZATION, authHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"NoHdr\",\"code\":\"NH%d\"}".formatted(n)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
        assertThat(TenantContext.runAsSystem(() -> branches.count())).isEqualTo(before);

        String created = mockMvc.perform(post("/api/v1/branches").header(HttpHeaders.AUTHORIZATION, authHeader(token))
                        .header("X-Branch-Id", home).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"WithHdr\",\"code\":\"WH%d\"}".formatted(n)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        UUID newId = UUID.fromString(JsonPath.read(created, "$.id"));
        createdBranches.add(newId);

        assertThat(TenantContext.runAsSystem(() -> grants.findByUserIdAndBranchId(UUID.fromString(userId), newId)))
                .hasValueSatisfying(g -> assertThat(g.getRoleId()).isEqualTo(UUID.fromString(roleId)));
    }

    @Test
    void creatorWhoseOnlyOwnerGrantIsOnADeletedBranch_doesNotBecomeOwnerOfANewBranch() throws Exception {
        String admin = adminToken();
        String home = seededBranch(admin);
        String dead = createBranch(admin);
        int n = SEQ.incrementAndGet();
        String roleId = JsonPath.read(postJson(admin, "/api/v1/roles",
                "{\"name\":\"Opener%d\",\"description\":\"d\",\"permissions\":[\"branch:read\",\"branch:write\"]}"
                        .formatted(n)), "$.id");
        String userId = JsonPath.read(postJson(admin, "/api/v1/users",
                "{\"email\":\"opener%d@t.test\",\"password\":\"password123\",\"fullName\":\"O\"}".formatted(n)), "$.id");
        mockMvc.perform(post("/api/v1/users/" + userId + "/access")
                .header(HttpHeaders.AUTHORIZATION, authHeader(admin)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"branchId\":\"%s\",\"roleId\":\"%s\"}".formatted(home, roleId)))
                .andExpect(status().isOk());
        UUID companyId = branch(home).getCompanyId();
        // a leftover from before grants were dropped on delete: seeded straight into the table
        TenantContext.runAs(companyId, () -> grants.save(new UserBranchAccess(UUID.fromString(userId),
                UUID.fromString(dead), roles.findByName("OWNER").orElseThrow().getId())));
        TenantContext.runAsSystem(() -> branches.delete(branch(dead)));
        String token = accessToken("opener%d@t.test".formatted(n), "password123");

        String created = mockMvc.perform(post("/api/v1/branches").header(HttpHeaders.AUTHORIZATION, authHeader(token))
                        .header("X-Branch-Id", home).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Opened\",\"code\":\"OP%d\"}".formatted(n)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        UUID newId = UUID.fromString(JsonPath.read(created, "$.id"));
        createdBranches.add(newId);

        assertThat(TenantContext.runAsSystem(() -> grants.findByUserIdAndBranchId(UUID.fromString(userId), newId)))
                .hasValueSatisfying(g -> assertThat(g.getRoleId()).isEqualTo(UUID.fromString(roleId)));
    }

    @Test
    void creatingAnInactiveBranch_writesNoGrant() throws Exception {
        int n = SEQ.incrementAndGet();
        String body = postJson(adminToken(), "/api/v1/branches",
                "{\"name\":\"Dormant %d\",\"code\":\"DO%d\",\"active\":false}".formatted(n, n));
        UUID id = UUID.fromString(JsonPath.read(body, "$.id"));
        createdBranches.add(id);

        assertThat(branch(id.toString()).isActive()).isFalse();
        assertThat(TenantContext.runAsSystem(() -> grants.findByBranchId(id))).isEmpty();
    }
}
