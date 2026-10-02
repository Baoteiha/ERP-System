package vn.essvn.erpcafe.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import com.jayway.jsonpath.JsonPath;

import vn.essvn.erpcafe.config.OwnerPoolProperties;
import vn.essvn.erpcafe.support.AbstractIntegrationTest;

/**
 * Refresh-token reuse detection (AUTH-G10).
 *
 * <p>Rotation alone only rejects the replayed token. A token that is presented
 * after it was already rotated away is the signal that it was stolen, so the
 * whole descendant family must die with it.
 */
class RefreshTokenReuseIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private OwnerPoolProperties ownerPool;

    private int refreshStatus(String token) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"%s\"}".formatted(token)))
                .andReturn().getResponse().getStatus();
    }

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

    @Test
    void moreReplaysAtOnceThanOwnerConnections_eachFailCleanly_andRevokeEveryFamily() throws Exception {
        int families = ownerPool.maximumPoolSize() + 2;
        List<String[]> stolen = new ArrayList<>();
        for (int i = 0; i < families; i++) {
            String first = JsonPath.read(loginBody("admin@esscafe.vn", "admin1234"), "$.refreshToken");
            stolen.add(new String[] {first, JsonPath.read(refreshOk(first), "$.refreshToken")});
        }

        // every family's rotated-away token replayed twice, all at once
        ExecutorService pool = Executors.newFixedThreadPool(families * 2);
        CountDownLatch start = new CountDownLatch(1);
        List<Integer> statuses = new ArrayList<>();
        try {
            List<Future<Integer>> replays = new ArrayList<>();
            for (String[] family : stolen) {
                for (int k = 0; k < 2; k++) {
                    replays.add(pool.submit(() -> {
                        start.await(10, TimeUnit.SECONDS);
                        return refreshStatus(family[0]);
                    }));
                }
            }
            start.countDown();
            for (Future<Integer> replay : replays) {
                statuses.add(replay.get(90, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(statuses).hasSize(families * 2).containsOnly(422);
        for (String[] family : stolen) {
            refreshRejected(family[1]);
        }
    }
}
