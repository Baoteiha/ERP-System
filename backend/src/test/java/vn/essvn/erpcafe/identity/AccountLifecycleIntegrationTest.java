package vn.essvn.erpcafe.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import com.jayway.jsonpath.JsonPath;

import vn.essvn.erpcafe.common.context.TenantContext;
import vn.essvn.erpcafe.identity.persistence.UserRepository;
import vn.essvn.erpcafe.support.AbstractIntegrationTest;

/**
 * Account lifecycle: profile edit, enable/disable, password change and reset
 * (AUTH-G16). None of these operations existed: {@code UserStatus.DISABLED} and
 * the password setter were defined but never called from anywhere.
 */
class AccountLifecycleIntegrationTest extends AbstractIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired
    private UserRepository userRepository;

    /** Creates a user in the admin's own company and returns its id. */
    private String createUser(String password) throws Exception {
        int n = SEQ.incrementAndGet();
        String json = mockMvc.perform(post("/api/v1/users")
                        .header(HttpHeaders.AUTHORIZATION, authHeader(adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"staff%d@esscafe.vn","password":"%s","fullName":"Staff %d"}
                                """.formatted(n, password, n)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(json, "$.id");
    }

    private String emailOf(String id) {
        return TenantContext.runAsSystem(() -> userRepository.findById(UUID.fromString(id))).orElseThrow().getEmail();
    }

    @Test
    void updateProfile_changesNameAndEmail() throws Exception {
        String id = createUser("initial12345");

        mockMvc.perform(put("/api/v1/users/" + id)
                        .header(HttpHeaders.AUTHORIZATION, authHeader(adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"renamed%d@esscafe.vn\",\"fullName\":\"Renamed\"}"
                                .formatted(SEQ.incrementAndGet())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fullName").value("Renamed"));
    }

    @Test
    void disable_blocksLoginAndRevokesRefreshTokens() throws Exception {
        String id = createUser("workingpw123");
        String email = emailOf(id);

        String refresh = refreshTokenOf(email, "workingpw123");

        mockMvc.perform(put("/api/v1/users/" + id + "/status")
                        .header(HttpHeaders.AUTHORIZATION, authHeader(adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"DISABLED\"}"))
                .andExpect(status().isOk());

        // the password is still correct, but the account is disabled
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"workingpw123\"}".formatted(email)))
                .andExpect(status().isUnprocessableEntity());

        // the session issued before disabling can no longer be extended
        mockMvc.perform(refreshRequest(refresh))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void enable_restoresLogin() throws Exception {
        String id = createUser("workingpw123");
        String email = emailOf(id);

        for (String status : new String[] {"DISABLED", "ACTIVE"}) {
            mockMvc.perform(put("/api/v1/users/" + id + "/status")
                            .header(HttpHeaders.AUTHORIZATION, authHeader(adminToken()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"status\":\"%s\"}".formatted(status)))
                    .andExpect(status().isOk());
        }

        assertThat(accessToken(email, "workingpw123")).isNotBlank();
    }

    @Test
    void disablingYourself_isRejected() throws Exception {
        String me = mockMvc.perform(get("/api/v1/auth/me")
                        .header(HttpHeaders.AUTHORIZATION, authHeader(adminToken())))
                .andReturn().getResponse().getContentAsString();
        String meId = JsonPath.read(me, "$.userId");

        mockMvc.perform(put("/api/v1/users/" + meId + "/status")
                        .header(HttpHeaders.AUTHORIZATION, authHeader(adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"DISABLED\"}"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void changeOwnPassword_requiresTheCurrentOne_andRotatesCredentials() throws Exception {
        String id = createUser("oldpassword1");
        String email = emailOf(id);
        String token = accessToken(email, "oldpassword1");

        mockMvc.perform(post("/api/v1/users/me/password")
                        .header(HttpHeaders.AUTHORIZATION, authHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"wrongpassword\",\"newPassword\":\"newpassword1\"}"))
                .andExpect(status().isUnprocessableEntity());

        mockMvc.perform(post("/api/v1/users/me/password")
                        .header(HttpHeaders.AUTHORIZATION, authHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"oldpassword1\",\"newPassword\":\"newpassword1\"}"))
                .andExpect(status().isNoContent());

        assertThat(accessToken(email, "newpassword1")).isNotBlank();
    }

    @Test
    void adminResetPassword_setsANewOne() throws Exception {
        String id = createUser("forgotten123");
        String email = emailOf(id);

        mockMvc.perform(put("/api/v1/users/" + id + "/password")
                        .header(HttpHeaders.AUTHORIZATION, authHeader(adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newPassword\":\"resetbyadmin1\"}"))
                .andExpect(status().isNoContent());

        assertThat(accessToken(email, "resetbyadmin1")).isNotBlank();
    }
}
