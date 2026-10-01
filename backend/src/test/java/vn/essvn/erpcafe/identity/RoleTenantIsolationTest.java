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

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import com.jayway.jsonpath.JsonPath;

import vn.essvn.erpcafe.common.context.TenantContext;
import vn.essvn.erpcafe.identity.domain.Role;
import vn.essvn.erpcafe.identity.domain.User;
import vn.essvn.erpcafe.identity.domain.UserBranchAccess;
import vn.essvn.erpcafe.identity.persistence.RoleRepository;
import vn.essvn.erpcafe.identity.persistence.UserBranchAccessRepository;
import vn.essvn.erpcafe.identity.persistence.UserRepository;
import vn.essvn.erpcafe.organization.domain.Branch;
import vn.essvn.erpcafe.organization.domain.Company;
import vn.essvn.erpcafe.organization.persistence.BranchRepository;
import vn.essvn.erpcafe.organization.persistence.CompanyRepository;
import vn.essvn.erpcafe.support.AbstractIntegrationTest;

/**
 * Class: RoleTenantIsolationTest
 * Purpose: Proves role, user-access and grant lookups answer 404 for another company's rows and leave them unchanged.
 */
class RoleTenantIsolationTest extends AbstractIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired private CompanyRepository companyRepository;
    @Autowired private BranchRepository branchRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private UserBranchAccessRepository accessRepository;

    private record Foreign(UUID roleId, UUID userId, UUID branchId) {
    }

    /** Seeds company B with a role, a user and a grant of that role at B's branch. */
    private Foreign seedForeign() {
        int n = SEQ.incrementAndGet();
        Company b = TenantContext.runAsSystem(
                () -> companyRepository.save(new Company("Role Other %d".formatted(n), "ROL%d".formatted(n))));
        return TenantContext.runAs(b.getId(), () -> {
            Branch branchB = branchRepository.save(new Branch("B Branch", "RLB%d".formatted(n)));
            Role roleB = roleRepository.save(new Role("B-ROLE-%d".formatted(n), "foreign"));
            User userB = userRepository.save(new User("role-foreign%d@other.test".formatted(n), "x", "B User"));
            accessRepository.save(new UserBranchAccess(userB.getId(), branchB.getId(), roleB.getId()));
            return new Foreign(roleB.getId(), userB.getId(), branchB.getId());
        });
    }

    private String auth() throws Exception {
        return authHeader(adminToken());
    }

    private String ownBranch() throws Exception {
        String me = mockMvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, auth()))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.<List<String>>read(me, "$.branchIds").get(0);
    }

    @Test
    void roleGet_rejectsAnotherCompanysRole() throws Exception {
        Foreign f = seedForeign();

        mockMvc.perform(get("/api/v1/roles/" + f.roleId()).header(HttpHeaders.AUTHORIZATION, auth()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void roleRename_rejectsAnotherCompanysRole() throws Exception {
        Foreign f = seedForeign();

        mockMvc.perform(put("/api/v1/roles/" + f.roleId()).header(HttpHeaders.AUTHORIZATION, auth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"HIJACKED\",\"description\":\"x\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        Role after = TenantContext.runAsSystem(() -> roleRepository.findById(f.roleId())).orElseThrow();
        assertThat(after.getName()).startsWith("B-ROLE-");
    }

    @Test
    void roleDelete_rejectsAnotherCompanysRole() throws Exception {
        Foreign f = seedForeign();

        mockMvc.perform(delete("/api/v1/roles/" + f.roleId()).header(HttpHeaders.AUTHORIZATION, auth()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        assertThat(TenantContext.runAsSystem(() -> roleRepository.findById(f.roleId()))).isPresent();
    }

    @Test
    void rolePermissionsReplace_rejectsAnotherCompanysRole() throws Exception {
        Foreign f = seedForeign();

        mockMvc.perform(put("/api/v1/roles/" + f.roleId() + "/permissions")
                        .header(HttpHeaders.AUTHORIZATION, auth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"permissions\":[\"user:read\"]}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        assertThat(TenantContext.runAsSystem(() -> roleRepository.findById(f.roleId())).orElseThrow()
                .getPermissions()).isEmpty();
    }

    @Test
    void userAccessGet_rejectsAnotherCompanysUser() throws Exception {
        Foreign f = seedForeign();

        mockMvc.perform(get("/api/v1/users/" + f.userId() + "/access").header(HttpHeaders.AUTHORIZATION, auth()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void accessRevoke_rejectsAnotherCompanysUserAndKeepsTheGrant() throws Exception {
        Foreign f = seedForeign();

        mockMvc.perform(delete("/api/v1/users/" + f.userId() + "/access/" + f.branchId())
                        .header(HttpHeaders.AUTHORIZATION, auth()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        assertThat(TenantContext.runAsSystem(() -> accessRepository.findByUserIdAndBranchId(f.userId(), f.branchId())))
                .isPresent();
    }

    @Test
    void grantAccess_rejectsAnotherCompanysRole() throws Exception {
        Foreign f = seedForeign();
        String email = "role-own%d@esscafe.test".formatted(SEQ.incrementAndGet());
        String created = mockMvc.perform(post("/api/v1/users").header(HttpHeaders.AUTHORIZATION, auth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"password123\",\"fullName\":\"Own\"}".formatted(email)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID ownUser = UUID.fromString(JsonPath.read(created, "$.id"));

        mockMvc.perform(post("/api/v1/users/" + ownUser + "/access").header(HttpHeaders.AUTHORIZATION, auth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"branchId\":\"%s\",\"roleId\":\"%s\"}".formatted(ownBranch(), f.roleId())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        assertThat(TenantContext.runAsSystem(() -> accessRepository.findByUserId(ownUser))).isEmpty();
    }
}
