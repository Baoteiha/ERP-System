package vn.essvn.erpcafe.identity;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

import vn.essvn.erpcafe.support.AbstractIntegrationTest;

/**
 * Class: PasswordResetDisabledTest
 * Purpose: Proves that with password reset switched off, as in prod, both of its endpoints answer not found.
 */
@TestPropertySource(properties = "app.security.password-reset.enabled=false")
class PasswordResetDisabledTest extends AbstractIntegrationTest {

    @Test
    void aResetRequest_isNotFound() throws Exception {
        mockMvc.perform(post("/api/v1/auth/password-reset/request").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"admin@esscafe.vn\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void aResetConfirmation_isNotFound() throws Exception {
        mockMvc.perform(post("/api/v1/auth/password-reset/confirm").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"%s\",\"newPassword\":\"new-pass-1\"}".formatted("a".repeat(64))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }
}
