package vn.essvn.erpcafe.identity;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import com.jayway.jsonpath.JsonPath;

import vn.essvn.erpcafe.support.AbstractIntegrationTest;

/**
 * Refresh-token reuse detection (AUTH-G10).
 *
 * <p>Rotation alone only rejects the replayed token. A token that is presented
 * after it was already rotated away is the signal that it was stolen, so the
 * whole descendant family must die with it.
 */
class RefreshTokenReuseIntegrationTest extends AbstractIntegrationTest {

    private String refreshOk(String token) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"%s\"}".formatted(token)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private void refreshRejected(String token) throws Exception {
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"%s\"}".formatted(token)))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void replayingARotatedToken_revokesTheWholeFamily() throws Exception {
        String first = JsonPath.read(loginBody("admin@esscafe.vn", "admin1234"), "$.refreshToken");

        // the legitimate client rotates once; `second` is now the live token
        String second = JsonPath.read(refreshOk(first), "$.refreshToken");

        // an attacker replays the stolen, already-rotated token
        refreshRejected(first);

        // the legitimate client's live token must now be dead too
        refreshRejected(second);
    }

    @Test
    void anUnrelatedSession_survivesAnotherFamilysCompromise() throws Exception {
        String sessionA = JsonPath.read(loginBody("admin@esscafe.vn", "admin1234"), "$.refreshToken");
        String sessionB = JsonPath.read(loginBody("admin@esscafe.vn", "admin1234"), "$.refreshToken");

        // compromise and burn family A
        String rotatedA = JsonPath.read(refreshOk(sessionA), "$.refreshToken");
        refreshRejected(sessionA);
        refreshRejected(rotatedA);

        // a separate login is its own family and must be unaffected
        refreshOk(sessionB);
    }
}
