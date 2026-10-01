package vn.essvn.erpcafe.organization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
import vn.essvn.erpcafe.organization.domain.Branch;
import vn.essvn.erpcafe.organization.domain.Company;
import vn.essvn.erpcafe.organization.persistence.BranchRepository;
import vn.essvn.erpcafe.organization.persistence.CompanyRepository;
import vn.essvn.erpcafe.support.AbstractIntegrationTest;

/**
 * Tenant isolation on the organization module's own endpoints.
 *
 * <p>Company and branch reads and writes must resolve only within the caller's
 * company. A second company is seeded directly through the repositories, then
 * observed through HTTP, which must not disclose or mutate it.
 */
class OrganizationTenantIsolationTest extends AbstractIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired
    private CompanyRepository companyRepository;

    @Autowired
    private BranchRepository branchRepository;

    protected Company seedForeignCompany() {
        int n = SEQ.incrementAndGet();
        return companyRepository.save(new Company("Foreign %d".formatted(n), "FGN%d".formatted(n)));
    }

    private Branch seedForeignBranch() {
        Company foreign = seedForeignCompany();
        return TenantContext.runAs(foreign.getId(),
                () -> branchRepository.save(new Branch("Foreign Outlet", "FOUT")));
    }

    @Test
    void companyList_returnsOnlyTheCallersOwnCompany() throws Exception {
        Company foreign = seedForeignCompany();

        String json = mockMvc.perform(get("/api/v1/companies?size=1000")
                        .header(HttpHeaders.AUTHORIZATION, authHeader(adminToken())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        List<String> codes = JsonPath.read(json, "$.content[*].code");
        assertThat(codes).doesNotContain(foreign.getCode());
        assertThat(codes).hasSize(1);
    }

    @Test
    void companyGet_rejectsAnotherCompany() throws Exception {
        UUID foreignId = seedForeignCompany().getId();

        mockMvc.perform(get("/api/v1/companies/" + foreignId)
                        .header(HttpHeaders.AUTHORIZATION, authHeader(adminToken())))
                .andExpect(status().isNotFound());
    }

    @Test
    void companyDelete_rejectsAnotherCompany() throws Exception {
        Company foreign = seedForeignCompany();

        mockMvc.perform(delete("/api/v1/companies/" + foreign.getId())
                        .header(HttpHeaders.AUTHORIZATION, authHeader(adminToken())))
                .andExpect(status().isNotFound());

        assertThat(companyRepository.findById(foreign.getId())).isPresent();
    }

    @Test
    void branchList_doesNotLeakBranchesFromAnotherCompany() throws Exception {
        Branch foreign = seedForeignBranch();

        String json = mockMvc.perform(get("/api/v1/branches?size=1000")
                        .header(HttpHeaders.AUTHORIZATION, authHeader(adminToken())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        List<String> ids = JsonPath.read(json, "$.content[*].id");
        assertThat(ids).doesNotContain(foreign.getId().toString());
    }

    @Test
    void branchGet_rejectsBranchFromAnotherCompany() throws Exception {
        UUID foreignId = seedForeignBranch().getId();

        mockMvc.perform(get("/api/v1/branches/" + foreignId)
                        .header(HttpHeaders.AUTHORIZATION, authHeader(adminToken())))
                .andExpect(status().isNotFound());
    }

    @Test
    void branchUpdate_rejectsBranchFromAnotherCompany() throws Exception {
        Branch foreign = seedForeignBranch();

        mockMvc.perform(put("/api/v1/branches/" + foreign.getId())
                        .header(HttpHeaders.AUTHORIZATION, authHeader(adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Hijacked\",\"code\":\"HIJ\"}"))
                .andExpect(status().isNotFound());

        assertThat(TenantContext.runAsSystem(() -> branchRepository.findById(foreign.getId())).orElseThrow()
                .getName()).isEqualTo("Foreign Outlet");
    }

    @Test
    void branchDelete_rejectsBranchFromAnotherCompany() throws Exception {
        Branch foreign = seedForeignBranch();

        mockMvc.perform(delete("/api/v1/branches/" + foreign.getId())
                        .header(HttpHeaders.AUTHORIZATION, authHeader(adminToken())))
                .andExpect(status().isNotFound());

        assertThat(TenantContext.runAsSystem(() -> branchRepository.findById(foreign.getId()))).isPresent();
    }

    @Test
    void branchCreate_assignsTheCallersOwnCompany() throws Exception {
        String created = mockMvc.perform(post("/api/v1/branches")
                        .header(HttpHeaders.AUTHORIZATION, authHeader(adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"New Outlet\",\"code\":\"NEW%d\"}".formatted(SEQ.incrementAndGet())))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        String me = mockMvc.perform(get("/api/v1/auth/me")
                        .header(HttpHeaders.AUTHORIZATION, authHeader(adminToken())))
                .andReturn().getResponse().getContentAsString();

        assertThat(JsonPath.read(created, "$.companyId").toString())
                .isEqualTo(JsonPath.read(me, "$.companyId").toString());

        // the admin became OWNER of the new branch: remove it so the shared admin keeps its own branches
        mockMvc.perform(delete("/api/v1/branches/" + JsonPath.read(created, "$.id"))
                .header(HttpHeaders.AUTHORIZATION, authHeader(adminToken()))).andExpect(status().isNoContent());
    }
}
