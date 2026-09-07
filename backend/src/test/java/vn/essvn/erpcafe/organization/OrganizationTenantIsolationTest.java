package vn.essvn.erpcafe.organization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;

import com.jayway.jsonpath.JsonPath;

import vn.essvn.erpcafe.organization.domain.Company;
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

    protected Company seedForeignCompany() {
        int n = SEQ.incrementAndGet();
        return companyRepository.save(new Company("Foreign %d".formatted(n), "FGN%d".formatted(n)));
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
}
