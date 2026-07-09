package vn.essvn.erpcafe.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import com.jayway.jsonpath.JsonPath;

import vn.essvn.erpcafe.support.AbstractIntegrationTest;

/**
 * End-to-end auth + authorization tests: login, token handling, RBAC, and
 * multi-branch scoping — the behaviour that is most costly to get wrong.
 */
class AuthFlowIntegrationTest extends AbstractIntegrationTest {

    private static final String ADMIN_EMAIL = "admin@esscafe.vn";
    private static final String ADMIN_PASSWORD = "admin1234";

    // --- helpers ---

    private String json(String body) throws Exception {
        return body;
    }

    private String login(String email, String password) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private String adminAccessToken() throws Exception {
        return JsonPath.read(login(ADMIN_EMAIL, ADMIN_PASSWORD), "$.accessToken");
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    // --- tests ---

    @Test
    void login_withValidCredentials_returnsTokens() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(ADMIN_EMAIL, ADMIN_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                .andExpect(jsonPath("$.tokenType").value("Bearer"));
    }

    @Test
    void login_withWrongPassword_returns422() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"nope\"}".formatted(ADMIN_EMAIL)))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void protectedEndpoint_withoutToken_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/branches"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void admin_canListBranches() throws Exception {
        mockMvc.perform(get("/api/v1/branches").header(HttpHeaders.AUTHORIZATION, bearer(adminAccessToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray());
    }

    @Test
    void createBranch_recordsAuthenticatedUserAsCreatedBy() throws Exception {
        String token = adminAccessToken();
        String companyId = JsonPath.read(
                mockMvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                        .andReturn().getResponse().getContentAsString(),
                "$.companyId");

        mockMvc.perform(post("/api/v1/branches")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("{\"companyId\":\"%s\",\"name\":\"Test Outlet\",\"code\":\"TST\"}".formatted(companyId))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.createdBy").value(ADMIN_EMAIL))
                .andExpect(jsonPath("$.code").value("TST"));
    }

    @Test
    void branchScoping_allowsAccessibleBranch_andForbidsOthers() throws Exception {
        String token = adminAccessToken();
        String meJson = mockMvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andReturn().getResponse().getContentAsString();
        List<String> branchIds = JsonPath.read(meJson, "$.branchIds");
        String accessible = branchIds.get(0);
        String inaccessible = "00000000-0000-0000-0000-000000000999";

        mockMvc.perform(get("/api/v1/branches")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .header("X-Branch-Id", accessible))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/branches")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .header("X-Branch-Id", inaccessible))
                .andExpect(status().isForbidden());
    }

    @Test
    void cashier_hasBranchReadButNotWriteOrUserRead() throws Exception {
        String adminToken = adminAccessToken();

        // create a cashier user
        String userJson = mockMvc.perform(post("/api/v1/users")
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"cashier1@esscafe.vn\",\"password\":\"cashier123\",\"fullName\":\"Cash Test\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String userId = JsonPath.read(userJson, "$.id");

        // find CASHIER role + admin's branch, then grant
        String rolesJson = mockMvc.perform(get("/api/v1/roles").header(HttpHeaders.AUTHORIZATION, bearer(adminToken)))
                .andReturn().getResponse().getContentAsString();
        List<String> cashierRoleIds = JsonPath.read(rolesJson, "$[?(@.name=='CASHIER')].id");
        String cashierRoleId = cashierRoleIds.get(0);
        String meJson = mockMvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, bearer(adminToken)))
                .andReturn().getResponse().getContentAsString();
        List<String> adminBranchIds = JsonPath.read(meJson, "$.branchIds");
        String branchId = adminBranchIds.get(0);

        mockMvc.perform(post("/api/v1/users/" + userId + "/access")
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"branchId\":\"%s\",\"roleId\":\"%s\"}".formatted(branchId, cashierRoleId)))
                .andExpect(status().isOk());

        // login as cashier and assert RBAC
        String cashierToken = JsonPath.read(login("cashier1@esscafe.vn", "cashier123"), "$.accessToken");

        mockMvc.perform(get("/api/v1/branches").header(HttpHeaders.AUTHORIZATION, bearer(cashierToken)))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/branches")
                        .header(HttpHeaders.AUTHORIZATION, bearer(cashierToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"companyId\":\"00000000-0000-0000-0000-000000000000\",\"name\":\"X\",\"code\":\"XX\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/users").header(HttpHeaders.AUTHORIZATION, bearer(cashierToken)))
                .andExpect(status().isForbidden());
    }

    @Test
    void refreshToken_rotatesAndLogoutRevokes() throws Exception {
        String loginJson = login(ADMIN_EMAIL, ADMIN_PASSWORD);
        String refresh = JsonPath.read(loginJson, "$.refreshToken");

        // refresh -> new tokens
        String refreshedJson = mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"%s\"}".formatted(refresh)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String newRefresh = JsonPath.read(refreshedJson, "$.refreshToken");

        // the old (rotated) token can no longer be used
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"%s\"}".formatted(refresh)))
                .andExpect(status().isUnprocessableEntity());

        // logout revokes the new token
        mockMvc.perform(post("/api/v1/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"%s\"}".formatted(newRefresh)))
                .andExpect(status().isNoContent());
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"%s\"}".formatted(newRefresh)))
                .andExpect(status().isUnprocessableEntity());

        assertThat(newRefresh).isNotEqualTo(refresh);
    }
}
