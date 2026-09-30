package vn.essvn.erpcafe.common;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import com.jayway.jsonpath.JsonPath;

import vn.essvn.erpcafe.support.AbstractIntegrationTest;

/**
 * Class: ErrorContractIntegrationTest
 * Purpose: Pins the HTTP status and the {@code code} property of every error the API can return.
 */
class ErrorContractIntegrationTest extends AbstractIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Test
    void unreadableBody_is400BadRequest() throws Exception {
        mockMvc.perform(post("/api/v1/ingredients")
                        .header(HttpHeaders.AUTHORIZATION, authHeader(adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }

    @Test
    void malformedId_is400BadRequest() throws Exception {
        mockMvc.perform(get("/api/v1/ingredients/not-a-uuid")
                        .header(HttpHeaders.AUTHORIZATION, authHeader(adminToken())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }

    @Test
    void missingBranchHeader_is400BadRequest() throws Exception {
        mockMvc.perform(get("/api/v1/reports/summary")
                        .header(HttpHeaders.AUTHORIZATION, authHeader(adminToken())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }

    @Test
    void unknownRoute_is404NotFound() throws Exception {
        mockMvc.perform(get("/api/v1/no-such-route")
                        .header(HttpHeaders.AUTHORIZATION, authHeader(adminToken())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void missingPermission_is403Forbidden() throws Exception {
        String adminToken = adminToken();
        int n = SEQ.incrementAndGet();
        String email = "contract-cashier%d@esscafe.vn".formatted(n);

        String userJson = mockMvc.perform(post("/api/v1/users")
                        .header(HttpHeaders.AUTHORIZATION, authHeader(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"cashier123\",\"fullName\":\"Cash Test\"}".formatted(email)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String userId = JsonPath.read(userJson, "$.id");

        String rolesJson = mockMvc.perform(get("/api/v1/roles").header(HttpHeaders.AUTHORIZATION, authHeader(adminToken)))
                .andReturn().getResponse().getContentAsString();
        List<String> cashierRoleIds = JsonPath.read(rolesJson, "$[?(@.name=='CASHIER')].id");
        String meJson = mockMvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, authHeader(adminToken)))
                .andReturn().getResponse().getContentAsString();
        List<String> branchIds = JsonPath.read(meJson, "$.branchIds");

        mockMvc.perform(post("/api/v1/users/" + userId + "/access")
                        .header(HttpHeaders.AUTHORIZATION, authHeader(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"branchId\":\"%s\",\"roleId\":\"%s\"}".formatted(branchIds.get(0), cashierRoleIds.get(0))))
                .andExpect(status().isOk());

        String cashierToken = accessToken(email, "cashier123");
        mockMvc.perform(get("/api/v1/users").header(HttpHeaders.AUTHORIZATION, authHeader(cashierToken)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void validationFailure_is400ValidationWithFieldErrors() throws Exception {
        mockMvc.perform(post("/api/v1/ingredients")
                        .header(HttpHeaders.AUTHORIZATION, authHeader(adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\",\"baseUnit\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION"))
                .andExpect(jsonPath("$.errors").isNotEmpty());
    }

    @Test
    void staleVersion_is409ConflictWithoutClassNames() throws Exception {
        String token = adminToken();
        int n = SEQ.incrementAndGet();
        String created = mockMvc.perform(post("/api/v1/ingredients")
                        .header(HttpHeaders.AUTHORIZATION, authHeader(token)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Contract%d\",\"baseUnit\":\"g\"}".formatted(n)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(created, "$.id");
        int v0 = (int) JsonPath.read(created, "$.version");

        mockMvc.perform(put("/api/v1/ingredients/" + id)
                        .header(HttpHeaders.AUTHORIZATION, authHeader(token)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Contract%d\",\"baseUnit\":\"kg\",\"version\":%d}".formatted(n, v0)))
                .andExpect(status().isOk());

        mockMvc.perform(put("/api/v1/ingredients/" + id)
                        .header(HttpHeaders.AUTHORIZATION, authHeader(token)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Contract%d\",\"baseUnit\":\"L\",\"version\":%d}".formatted(n, v0)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"))
                .andExpect(jsonPath("$.detail")
                        .value("This record was changed by someone else. Reload and try again."));
    }

    @Test
    void wrongPassword_is401Unauthorized() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"admin@esscafe.vn\",\"password\":\"nope\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }
}
