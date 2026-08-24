package vn.essvn.erpcafe.identity;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import com.jayway.jsonpath.JsonPath;

import vn.essvn.erpcafe.support.AbstractIntegrationTest;

/**
 * Tenant isolation on branch-access grants (AUTH-G01).
 *
 * <p>A user may only be granted access to a branch belonging to their own
 * company. Granting a company-A user access to a company-B branch would forge a
 * cross-tenant entry into the user's branch set, which every branch-scoped query
 * downstream then trusts — the escalation described in the auth spec.
 */
class UserAccessGrantIntegrationTest extends AbstractIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger();

    private String create(String token, String url, String body) throws Exception {
        return mockMvc.perform(post(url)
                        .header(HttpHeaders.AUTHORIZATION, authHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void grantAccess_rejectsBranchFromAnotherCompany() throws Exception {
        String token = adminToken(); // OWNER of the seeded company A
        int n = SEQ.incrementAndGet();

        // A role and a user, both in the admin's own company (A).
        String roleId = JsonPath.read(create(token, "/api/v1/roles",
                "{\"name\":\"Grantee%d\",\"description\":\"d\",\"permissions\":[\"branch:read\"]}".formatted(n)),
                "$.id");
        String userAId = JsonPath.read(create(token, "/api/v1/users",
                "{\"email\":\"grantee%d@a.test\",\"password\":\"password123\",\"fullName\":\"A User\"}".formatted(n)),
                "$.id");

        // A second company (B) with a branch of its own.
        String companyBId = JsonPath.read(create(token, "/api/v1/companies",
                "{\"name\":\"Company B %d\",\"code\":\"CB%d\"}".formatted(n, n)),
                "$.id");
        String branchBId = JsonPath.read(create(token, "/api/v1/branches",
                "{\"companyId\":\"%s\",\"name\":\"B Branch\",\"code\":\"BB%d\"}".formatted(companyBId, n)),
                "$.id");

        // Granting company A's user access to company B's branch must be refused.
        mockMvc.perform(post("/api/v1/users/" + userAId + "/access")
                        .header(HttpHeaders.AUTHORIZATION, authHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"branchId\":\"%s\",\"roleId\":\"%s\"}".formatted(branchBId, roleId)))
                .andExpect(status().isNotFound());
    }
}
