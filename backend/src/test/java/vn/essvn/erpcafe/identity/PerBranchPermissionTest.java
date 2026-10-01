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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.jayway.jsonpath.JsonPath;

import vn.essvn.erpcafe.identity.persistence.RoleRepository;
import vn.essvn.erpcafe.identity.persistence.UserBranchAccessRepository;
import vn.essvn.erpcafe.identity.persistence.UserRepository;
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

    /** An order at the branch, paid in full by the owner, so it is COMPLETED and refundable. */
    private String completedOrderAt(String branchId) throws Exception {
        int n = SEQ.incrementAndGet();
        String categoryId = JsonPath.read(json(send(post("/api/v1/categories"), admin, branchId,
                "{\"name\":\"PB Drinks %d\",\"displayOrder\":1}".formatted(n))), "$.id");
        String productId = JsonPath.read(json(send(post("/api/v1/products"), admin, branchId,
                "{\"categoryId\":\"%s\",\"name\":\"PB Tea %d\",\"sku\":\"PBT-%d\",\"basePrice\":20000}"
                        .formatted(categoryId, n, n))), "$.id");
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
        return users.findById(UUID.fromString(account.id())).orElseThrow().getPasswordHash();
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
}
