package vn.essvn.erpcafe.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

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
    @Autowired
    @Qualifier("ownerDataSource")
    private DataSource ownerDataSource;

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

    @Test
    void aRotationInFlightWhenTheFamilyIsRevoked_leavesNoLiveTokenInIt() throws Exception {
        String first = JsonPath.read(loginBody("admin@esscafe.vn", "admin1234"), "$.refreshToken");
        String second = JsonPath.read(refreshOk(first), "$.refreshToken");
        JdbcTemplate owner = new JdbcTemplate(ownerDataSource);
        UUID family = owner.queryForObject("SELECT family_id FROM refresh_token"
                + " WHERE token_hash = encode(sha256(?::bytea), 'hex')", UUID.class, second);

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection rotation = ownerDataSource.getConnection()) {
            // a refresh of `second` has rotated it away and written its replacement, but not committed yet
            rotation.setAutoCommit(false);
            try (PreparedStatement revoke = rotation.prepareStatement("UPDATE refresh_token SET revoked = true,"
                    + " version = version + 1 WHERE token_hash = encode(sha256(?::bytea), 'hex') RETURNING user_id")) {
                revoke.setString(1, second);
                try (ResultSet user = revoke.executeQuery()) {
                    user.next();
                    try (PreparedStatement replacement = rotation.prepareStatement("INSERT INTO refresh_token"
                            + " (id, version, user_id, token_hash, expires_at, family_id)"
                            + " VALUES (?, 0, ?, ?, now() + interval '1 day', ?)")) {
                        replacement.setObject(1, UUID.randomUUID());
                        replacement.setObject(2, user.getObject(1, UUID.class));
                        replacement.setString(3, "race-" + UUID.randomUUID());
                        replacement.setObject(4, family);
                        replacement.executeUpdate();
                    }
                }
            }

            // meanwhile the stolen `first` is replayed, and its family revoked
            Future<Integer> replay = pool.submit(() -> refreshStatus(first));
            awaitALockWait(owner);
            rotation.commit();

            assertThat(replay.get(30, TimeUnit.SECONDS)).isEqualTo(422);
        } finally {
            pool.shutdownNow();
        }
        assertThat(owner.queryForObject("SELECT count(*) FROM refresh_token WHERE family_id = ? AND revoked = false",
                Long.class, family)).isZero();
    }

    /** Waits until some session of this database waits on a row lock: the revocation, behind the rotation. */
    private static void awaitALockWait(JdbcTemplate owner) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (owner.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname = current_database()"
                + " AND wait_event_type = 'Lock'", Long.class) == 0) {
            assertThat(System.nanoTime()).as("the revocation waits for the rotation").isLessThan(deadline);
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(50));
        }
    }
}
