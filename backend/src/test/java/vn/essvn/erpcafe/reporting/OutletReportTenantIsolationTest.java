package vn.essvn.erpcafe.reporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;

import com.jayway.jsonpath.JsonPath;

import vn.essvn.erpcafe.organization.domain.Branch;
import vn.essvn.erpcafe.organization.domain.Company;
import vn.essvn.erpcafe.organization.persistence.BranchRepository;
import vn.essvn.erpcafe.organization.persistence.CompanyRepository;
import vn.essvn.erpcafe.support.AbstractIntegrationTest;

/**
 * Tenant isolation on the outlet-comparison report (AUTH-G04).
 *
 * <p>{@code /reports/outlets} must only expose the caller's own branches. A
 * second company's active branch is seeded, then the report is called as the
 * admin of company A and must not disclose it — the leak that also handed out
 * the foreign branch ids used in the G01 escalation.
 */
class OutletReportTenantIsolationTest extends AbstractIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired
    private CompanyRepository companyRepository;

    @Autowired
    private BranchRepository branchRepository;

    @Test
    void outlets_doesNotExposeAnotherCompanysBranch() throws Exception {
        int n = SEQ.incrementAndGet();
        // Class-specific code prefix: all integration tests share one DB, so codes must be globally unique.
        Company other = companyRepository.save(new Company("Outlet Other %d".formatted(n), "ORPT%d".formatted(n)));
        String foreignCode = "FB%d".formatted(n);
        branchRepository.save(new Branch(other.getId(), "Foreign Branch", foreignCode));

        String json = mockMvc.perform(get("/api/v1/reports/outlets")
                        .header(HttpHeaders.AUTHORIZATION, authHeader(adminToken())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        List<String> codes = JsonPath.read(json, "$[*].code");
        assertThat(codes).doesNotContain(foreignCode);
    }
}
