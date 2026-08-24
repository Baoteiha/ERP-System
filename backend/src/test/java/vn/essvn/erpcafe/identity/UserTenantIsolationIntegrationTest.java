package vn.essvn.erpcafe.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;

import com.jayway.jsonpath.JsonPath;

import vn.essvn.erpcafe.identity.domain.User;
import vn.essvn.erpcafe.identity.persistence.UserRepository;
import vn.essvn.erpcafe.organization.domain.Company;
import vn.essvn.erpcafe.organization.persistence.CompanyRepository;
import vn.essvn.erpcafe.support.AbstractIntegrationTest;

/**
 * Tenant isolation on user reads (AUTH-G02, AUTH-G03).
 *
 * <p>User listing and by-id lookup must be scoped to the caller's own company.
 * A second company's user is seeded directly, then observed through the HTTP
 * endpoints, which must not disclose it.
 */
class UserTenantIsolationIntegrationTest extends AbstractIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired
    private CompanyRepository companyRepository;

    @Autowired
    private UserRepository userRepository;

    /** Seeds a user belonging to a brand-new company (never the caller's). Returns its id + email. */
    private User seedForeignUser() {
        int n = SEQ.incrementAndGet();
        Company other = companyRepository.save(new Company("Other %d".formatted(n), "OTH%d".formatted(n)));
        return userRepository.save(new User(other.getId(), "foreign%d@other.test".formatted(n), "x", "Foreign User"));
    }

    @Test
    void list_doesNotLeakUsersFromAnotherCompany() throws Exception {
        User foreign = seedForeignUser();

        String json = mockMvc.perform(get("/api/v1/users?size=1000")
                        .header(HttpHeaders.AUTHORIZATION, authHeader(adminToken())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        List<String> emails = JsonPath.read(json, "$.content[*].email");
        assertThat(emails).doesNotContain(foreign.getEmail());
    }

    @Test
    void get_rejectsUserFromAnotherCompany() throws Exception {
        UUID foreignId = seedForeignUser().getId();

        mockMvc.perform(get("/api/v1/users/" + foreignId)
                        .header(HttpHeaders.AUTHORIZATION, authHeader(adminToken())))
                .andExpect(status().isNotFound());
    }
}
