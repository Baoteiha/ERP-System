package vn.essvn.erpcafe.inventory;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import com.jayway.jsonpath.JsonPath;

import vn.essvn.erpcafe.support.AbstractIntegrationTest;

/**
 * Stale-form protection: an update that echoes an out-of-date {@code version} is
 * rejected with 409 instead of silently overwriting a newer change. A matching
 * version succeeds and bumps the version; omitting the version stays backward-compatible.
 */
class InventoryOptimisticLockTest extends AbstractIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Test
    void staleIngredientUpdate_isRejectedWith409() throws Exception {
        String token = adminToken();
        int n = SEQ.incrementAndGet();

        String created = mockMvc.perform(post("/api/v1/ingredients")
                        .header(HttpHeaders.AUTHORIZATION, authHeader(token)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Beans%d\",\"baseUnit\":\"g\"}".formatted(n)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(created, "$.id");
        int v0 = (int) JsonPath.read(created, "$.version");

        // Correct version → succeeds and bumps the version.
        mockMvc.perform(putIngredient(token, id, n, "kg", v0))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(v0 + 1));

        // Same (now stale) version → 409, the newer change is protected.
        mockMvc.perform(putIngredient(token, id, n, "L", v0))
                .andExpect(status().isConflict());

        // Current version → succeeds again.
        mockMvc.perform(putIngredient(token, id, n, "L", v0 + 1))
                .andExpect(status().isOk());
    }

    @Test
    void updateWithoutVersion_stillWorks() throws Exception {
        String token = adminToken();
        int n = SEQ.incrementAndGet();

        String created = mockMvc.perform(post("/api/v1/ingredients")
                        .header(HttpHeaders.AUTHORIZATION, authHeader(token)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Oats%d\",\"baseUnit\":\"g\"}".formatted(n)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(created, "$.id");

        // No version supplied → the guard is skipped (backward-compatible).
        mockMvc.perform(put("/api/v1/ingredients/" + id)
                        .header(HttpHeaders.AUTHORIZATION, authHeader(token)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Oats%d\",\"baseUnit\":\"kg\"}".formatted(n)))
                .andExpect(status().isOk());
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder putIngredient(
            String token, String id, int n, String baseUnit, int version) {
        return put("/api/v1/ingredients/" + id)
                .header(HttpHeaders.AUTHORIZATION, authHeader(token)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Beans%d\",\"baseUnit\":\"%s\",\"version\":%d}".formatted(n, baseUnit, version));
    }
}
