package vn.essvn.erpcafe.inventory;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.HashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.jayway.jsonpath.JsonPath;

import vn.essvn.erpcafe.identity.domain.Role;
import vn.essvn.erpcafe.identity.domain.User;
import vn.essvn.erpcafe.identity.domain.UserBranchAccess;
import vn.essvn.erpcafe.identity.persistence.PermissionRepository;
import vn.essvn.erpcafe.identity.persistence.RoleRepository;
import vn.essvn.erpcafe.identity.persistence.UserBranchAccessRepository;
import vn.essvn.erpcafe.identity.persistence.UserRepository;
import vn.essvn.erpcafe.organization.domain.Branch;
import vn.essvn.erpcafe.organization.domain.Company;
import vn.essvn.erpcafe.organization.persistence.BranchRepository;
import vn.essvn.erpcafe.organization.persistence.CompanyRepository;
import vn.essvn.erpcafe.support.AbstractIntegrationTest;

/**
 * Tenant isolation for the inventory by-id endpoints. The bootstrap admin owns
 * "company A" resources; a freshly seeded second company ("company B") must not
 * be able to read, edit, or delete them by id. Cross-tenant access returns 404
 * (not 403) so the endpoint never even confirms the resource exists.
 */
class InventoryTenantIsolationTest extends AbstractIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired
    private CompanyRepository companyRepository;
    @Autowired
    private BranchRepository branchRepository;
    @Autowired
    private RoleRepository roleRepository;
    @Autowired
    private PermissionRepository permissionRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private UserBranchAccessRepository accessRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private PlatformTransactionManager txManager;

    @Test
    void ingredientEndpoints_areTenantIsolated() throws Exception {
        int n = SEQ.incrementAndGet();
        String admin = adminToken();
        TenantB b = seedOtherCompany(n);

        String ingredientId = JsonPath.read(createJson(admin, null, "/api/v1/ingredients",
                "{\"name\":\"BeansA%d\",\"baseUnit\":\"g\"}".formatted(n)), "$.id");

        // Company B cannot read / edit / delete Company A's ingredient.
        mockMvc.perform(get("/api/v1/ingredients/" + ingredientId)
                        .header(HttpHeaders.AUTHORIZATION, authHeader(b.token())))
                .andExpect(status().isNotFound());
        mockMvc.perform(put("/api/v1/ingredients/" + ingredientId)
                        .header(HttpHeaders.AUTHORIZATION, authHeader(b.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Hacked%d\",\"baseUnit\":\"g\"}".formatted(n)))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/v1/ingredients/" + ingredientId)
                        .header(HttpHeaders.AUTHORIZATION, authHeader(b.token())))
                .andExpect(status().isNotFound());

        // The owning company still sees it (fix didn't over-restrict).
        mockMvc.perform(get("/api/v1/ingredients/" + ingredientId)
                        .header(HttpHeaders.AUTHORIZATION, authHeader(admin)))
                .andExpect(status().isOk());
    }

    @Test
    void supplierEndpoints_areTenantIsolated() throws Exception {
        int n = SEQ.incrementAndGet();
        String admin = adminToken();
        TenantB b = seedOtherCompany(n);

        String supplierId = JsonPath.read(createJson(admin, null, "/api/v1/suppliers",
                "{\"name\":\"SupA%d\"}".formatted(n)), "$.id");

        mockMvc.perform(get("/api/v1/suppliers/" + supplierId)
                        .header(HttpHeaders.AUTHORIZATION, authHeader(b.token())))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/v1/suppliers/" + supplierId)
                        .header(HttpHeaders.AUTHORIZATION, authHeader(b.token())))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/v1/suppliers/" + supplierId)
                        .header(HttpHeaders.AUTHORIZATION, authHeader(admin)))
                .andExpect(status().isOk());
    }

    @Test
    void purchaseOrderEndpoints_areBranchIsolated() throws Exception {
        int n = SEQ.incrementAndGet();
        String admin = adminToken();
        String branchA = firstBranch(admin);
        TenantB b = seedOtherCompany(n);

        String supplierId = JsonPath.read(createJson(admin, branchA, "/api/v1/suppliers",
                "{\"name\":\"SupPO%d\"}".formatted(n)), "$.id");
        String ingredientId = JsonPath.read(createJson(admin, branchA, "/api/v1/ingredients",
                "{\"name\":\"BeansPO%d\",\"baseUnit\":\"g\"}".formatted(n)), "$.id");
        String poId = JsonPath.read(createJson(admin, branchA, "/api/v1/purchase-orders",
                "{\"supplierId\":\"%s\",\"lines\":[{\"ingredientId\":\"%s\",\"orderedQty\":10,\"unitCost\":1}]}"
                        .formatted(supplierId, ingredientId)), "$.id");

        // Company B, acting in its own (accessible) branch, cannot see or act on A's PO.
        mockMvc.perform(get("/api/v1/purchase-orders/" + poId)
                        .header(HttpHeaders.AUTHORIZATION, authHeader(b.token()))
                        .header("X-Branch-Id", b.branchId()))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/v1/purchase-orders/" + poId + "/cancel")
                        .header(HttpHeaders.AUTHORIZATION, authHeader(b.token()))
                        .header("X-Branch-Id", b.branchId()))
                .andExpect(status().isNotFound());

        // The owning branch still sees it.
        mockMvc.perform(get("/api/v1/purchase-orders/" + poId)
                        .header(HttpHeaders.AUTHORIZATION, authHeader(admin))
                        .header("X-Branch-Id", branchA))
                .andExpect(status().isOk());
    }

    // --- helpers ---

    private record TenantB(String token, String branchId) {
    }

    /** Seeds an independent company (its own branch, full-permission role, and a user) and logs in. */
    private TenantB seedOtherCompany(int n) throws Exception {
        String email = "owner%d@rival.test".formatted(n);
        String branchId = new TransactionTemplate(txManager).execute(statusTx -> {
            Company company = companyRepository.save(new Company("Rival Cafe " + n, "RIV" + n));
            Branch branch = branchRepository.save(new Branch(company.getId(), "Rival HQ " + n, "RHQ" + n));
            Role role = new Role(company.getId(), "TenantOwner" + n, "Full access");
            role.setPermissions(new HashSet<>(permissionRepository.findAll()));
            role = roleRepository.save(role);
            User user = userRepository.save(new User(company.getId(), email,
                    passwordEncoder.encode("rival1234"), "Rival Owner"));
            accessRepository.save(new UserBranchAccess(user.getId(), branch.getId(), role.getId()));
            return branch.getId().toString();
        });
        return new TenantB(accessToken(email, "rival1234"), branchId);
    }

    private String firstBranch(String token) throws Exception {
        String me = mockMvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, authHeader(token)))
                .andReturn().getResponse().getContentAsString();
        return ((List<String>) JsonPath.read(me, "$.branchIds")).get(0);
    }

    private String createJson(String token, String branchId, String url, String body) throws Exception {
        var req = post(url).header(HttpHeaders.AUTHORIZATION, authHeader(token))
                .contentType(MediaType.APPLICATION_JSON).content(body);
        if (branchId != null) {
            req.header("X-Branch-Id", branchId);
        }
        return mockMvc.perform(req).andExpect(status().is2xxSuccessful())
                .andReturn().getResponse().getContentAsString();
    }
}
