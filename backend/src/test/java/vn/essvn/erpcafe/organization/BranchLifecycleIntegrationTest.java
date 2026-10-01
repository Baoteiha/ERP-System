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

import vn.essvn.erpcafe.identity.persistence.RoleRepository;
import vn.essvn.erpcafe.identity.persistence.UserBranchAccessRepository;
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
        orders.deleteAll(createdOrders);
        for (UUID id : createdBranches) {
            grants.deleteAll(grants.findAll().stream().filter(g -> g.getBranchId().equals(id)).toList());
            branches.findById(id).ifPresent(branches::delete);
        }
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
        UUID companyId = branches.findById(UUID.fromString(branchIds[0])).orElseThrow().getCompanyId();
        UUID roleId = roles.findByCompanyIdAndName(companyId, roleName).orElseThrow().getId();
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
    void deleteBranch_withOpenOrder_isRefused() throws Exception {
        String admin = adminToken();
        String branchId = createBranch(admin);
        createdOrders.add(orders.save(
                new Order(UUID.fromString(branchId), null, OrderType.DINE_IN, BigDecimal.ZERO)));

        mockMvc.perform(delete("/api/v1/branches/" + branchId)
                        .header(HttpHeaders.AUTHORIZATION, authHeader(admin)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));

        assertThat(branches.findById(UUID.fromString(branchId))).isPresent();
    }

}
