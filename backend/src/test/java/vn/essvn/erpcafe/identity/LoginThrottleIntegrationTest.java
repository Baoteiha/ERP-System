package vn.essvn.erpcafe.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import vn.essvn.erpcafe.support.AbstractIntegrationTest;

/**
 * Repeated failed logins are throttled (AUTH-G11). The login endpoint is public
 * and previously allowed unbounded password attempts.
 */
class LoginThrottleIntegrationTest extends AbstractIntegrationTest {

    private int attemptLogin(String email, String password) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password)))
                .andReturn().getResponse().getStatus();
    }

    @Test
    void repeatedFailures_eventuallyReturn429() throws Exception {
        String email = "throttle-target@esscafe.vn";

        int lastStatus = 0;
        for (int i = 0; i < 15; i++) {
            lastStatus = attemptLogin(email, "definitely-wrong");
        }

        assertThat(lastStatus).isEqualTo(429);
    }

    @Test
    void aSuccessfulLogin_clearsTheCounter() throws Exception {
        // a few failures, well under the threshold, then a success
        for (int i = 0; i < 3; i++) {
            attemptLogin("admin@esscafe.vn", "wrong-password");
        }
        assertThat(attemptLogin("admin@esscafe.vn", "admin1234")).isEqualTo(200);

        // the counter reset, so the next failure is an ordinary rejection
        assertThat(attemptLogin("admin@esscafe.vn", "wrong-password")).isEqualTo(401);
    }
}
