package vn.essvn.erpcafe.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;

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

    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final String BY_HASH = "token_hash = encode(sha256(?::bytea), 'hex')";

    @Autowired
    private OwnerPoolProperties ownerPool;
    @Autowired
    @Qualifier("ownerDataSource")
    private DataSource ownerDataSource;

    private int refreshStatus(String token) throws Exception {
        return mockMvc.perform(refreshRequest(token)).andReturn().getResponse().getStatus();
    }

    private void refreshRejected(String token) throws Exception {
        mockMvc.perform(refreshRequest(token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    private JdbcTemplate owner() {
        return new JdbcTemplate(ownerDataSource);
    }

    private UUID familyOf(String token) {
        return owner().queryForObject("SELECT family_id FROM refresh_token WHERE " + BY_HASH, UUID.class, token);
    }

    private long replaysRecorded(UUID family) {
        return owner().queryForObject("SELECT count(*) FROM auth_event WHERE type = 'REFRESH_REUSE_DETECTED'"
                + " AND detail ->> 'session' = ?", Long.class, family.toString());
    }

    /** Every row of the token's family but its own, as it stands now. */
    private List<Map<String, Object>> restOfFamily(String token) {
        return owner().queryForList("SELECT id, revoked, version FROM refresh_token WHERE family_id = ?"
                + " AND NOT " + BY_HASH + " ORDER BY id", familyOf(token), token);
    }

    /**
     * Presents a token ended without being rotated away: refused as revoked, while a live token beside it in
     * the family, as another device would hold, and every other row of the family stay as they were.
     */
    private void refusedAsRevokedNotReplayed(String token) throws Exception {
        UUID family = familyOf(token);
        owner().update("INSERT INTO refresh_token (id, version, user_id, token_hash, expires_at, family_id)"
                + " SELECT ?, 0, user_id, ?, now() + interval '1 day', family_id FROM refresh_token WHERE " + BY_HASH,
                UUID.randomUUID(), "sibling-" + UUID.randomUUID(), token);
        List<Map<String, Object>> before = restOfFamily(token);

        MvcResult refused = mockMvc.perform(refreshRequest(token)).andReturn();

        assertThat(replaysRecorded(family)).as("REFRESH_REUSE_DETECTED rows").isZero();
        assertThat(restOfFamily(token)).isEqualTo(before);
        assertThat(refused.getResponse().getStatus()).isEqualTo(401);
        assertThat(JsonPath.<String>read(refused.getResponse().getContentAsString(), "$.code"))
                .isEqualTo("UNAUTHORIZED");
    }

    @Test
    void replayingARotatedToken_revokesTheWholeFamily() throws Exception {
        String first = refreshTokenOf("admin@esscafe.vn", "admin1234");
        UUID family = familyOf(first);

        // the legitimate client rotates once; `second` is now the live token
        String second = rotate(first);

        // an attacker replays the stolen, already-rotated token
        refreshRejected(first);

        assertThat(replaysRecorded(family)).isEqualTo(1);
        assertThat(owner().queryForObject("SELECT count(*) FROM refresh_token WHERE family_id = ? AND revoked = false",
                Long.class, family)).isZero();
        // the legitimate client's live token must now be dead too, and is refused as revoked, not replayed
        refreshRejected(second);
        assertThat(replaysRecorded(family)).isEqualTo(1);
    }

    @Test
    void aRefreshAfterLogout_isRefusedAsRevoked_notAsAReplay() throws Exception {
        String second = rotate(refreshTokenOf("admin@esscafe.vn", "admin1234"));
        mockMvc.perform(logoutRequest(second)).andExpect(status().isNoContent());

        refusedAsRevokedNotReplayed(second);
    }

    @Test
    void aRefreshAfterAnAdministrativeReset_isRefusedAsRevoked_notAsAReplay() throws Exception {
        String admin = adminToken();
        String email = "reuse%d@t.test".formatted(SEQ.incrementAndGet());
        String userId = JsonPath.read(mockMvc.perform(post("/api/v1/users")
                        .header(HttpHeaders.AUTHORIZATION, authHeader(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"password123\",\"fullName\":\"Reuse\"}"
                                .formatted(email)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
        String token = rotate(refreshTokenOf(email, "password123"));

        mockMvc.perform(put("/api/v1/users/" + userId + "/password")
                        .header(HttpHeaders.AUTHORIZATION, authHeader(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newPassword\":\"reset-by-admin-1\"}"))
                .andExpect(status().isNoContent());

        refusedAsRevokedNotReplayed(token);
    }

    @Test
    void aRefreshWithATokenV27Revoked_isRefusedAsRevoked_notAsAReplay() throws Exception {
        String token = rotate(refreshTokenOf("admin@esscafe.vn", "admin1234"));
        // V27_001's statement, narrowed to this token so no other session ends
        owner().update("UPDATE refresh_token SET revoked = TRUE, version = version + 1 WHERE revoked = FALSE AND "
                + BY_HASH, token);

        refusedAsRevokedNotReplayed(token);
    }

    @Test
    void anUnknownOrExpiredRefreshToken_isUnauthorized() throws Exception {
        refreshRejected("never-issued-" + UUID.randomUUID());

        String token = refreshTokenOf("admin@esscafe.vn", "admin1234");
        owner().update("UPDATE refresh_token SET expires_at = now() - interval '1 second' WHERE " + BY_HASH, token);
        refreshRejected(token);
    }

    @Test
    void anUnrelatedSession_survivesAnotherFamilysCompromise() throws Exception {
        String sessionA = refreshTokenOf("admin@esscafe.vn", "admin1234");
        String sessionB = refreshTokenOf("admin@esscafe.vn", "admin1234");

        // compromise and burn family A
        String rotatedA = rotate(sessionA);
        refreshRejected(sessionA);
        refreshRejected(rotatedA);

        // a separate login is its own family and must be unaffected
        rotate(sessionB);
    }

    @Test
    void moreReplaysAtOnceThanOwnerConnections_eachFailCleanly_andRevokeEveryFamily() throws Exception {
        int families = ownerPool.maximumPoolSize() + 2;
        List<String[]> stolen = new ArrayList<>();
        for (int i = 0; i < families; i++) {
            String first = refreshTokenOf("admin@esscafe.vn", "admin1234");
            stolen.add(new String[] {first, rotate(first)});
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

        assertThat(statuses).hasSize(families * 2).containsOnly(401);
        for (String[] family : stolen) {
            refreshRejected(family[1]);
        }
    }

    @Test
    void aRotationInFlightWhenTheFamilyIsRevoked_leavesNoLiveTokenInIt() throws Exception {
        String first = refreshTokenOf("admin@esscafe.vn", "admin1234");
        String second = rotate(first);
        JdbcTemplate owner = new JdbcTemplate(ownerDataSource);
        UUID family = owner.queryForObject("SELECT family_id FROM refresh_token"
                + " WHERE token_hash = encode(sha256(?::bytea), 'hex')", UUID.class, second);

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection rotation = ownerDataSource.getConnection()) {
            // a refresh of `second` has rotated it away and written its replacement, but not committed yet
            rotation.setAutoCommit(false);
            try (PreparedStatement revoke = rotation.prepareStatement("UPDATE refresh_token SET revoked = true,"
                    + " rotated_at = now(), version = version + 1 WHERE " + BY_HASH + " RETURNING user_id")) {
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

            assertThat(replay.get(30, TimeUnit.SECONDS)).isEqualTo(401);
        } finally {
            pool.shutdownNow();
        }
        assertThat(owner.queryForObject("SELECT count(*) FROM refresh_token WHERE family_id = ? AND revoked = false",
                Long.class, family)).isZero();
    }

    /**
     * Two tabs refreshing at the same instant send the same cookie. Both find it live; one rotation wins on
     * the row's version, and the loser is either refused with 409 (it lost the version race) or, if it read
     * the row after the winner committed, treated as a replay: 401 and the family revoked. Either way never a
     * 500 and never two replacements.
     */
    @Test
    void twoSimultaneousRefreshesOfOneToken_oneWins_andTheLoserFailsCleanly() throws Exception {
        for (int attempt = 0; attempt < 5; attempt++) {
            String token = refreshTokenOf("admin@esscafe.vn", "admin1234");
            JdbcTemplate owner = new JdbcTemplate(ownerDataSource);
            UUID family = owner.queryForObject("SELECT family_id FROM refresh_token"
                    + " WHERE token_hash = encode(sha256(?::bytea), 'hex')", UUID.class, token);

            ExecutorService pool = Executors.newFixedThreadPool(2);
            CountDownLatch start = new CountDownLatch(1);
            List<Integer> statuses = new ArrayList<>();
            try {
                List<Future<Integer>> both = new ArrayList<>();
                for (int k = 0; k < 2; k++) {
                    both.add(pool.submit(() -> {
                        start.await(10, TimeUnit.SECONDS);
                        return refreshStatus(token);
                    }));
                }
                start.countDown();
                for (Future<Integer> one : both) {
                    statuses.add(one.get(30, TimeUnit.SECONDS));
                }
            } finally {
                pool.shutdownNow();
            }

            assertThat(statuses).containsOnlyOnce(200).allMatch(code -> code == 200 || code == 409 || code == 401);
            long live = owner.queryForObject("SELECT count(*) FROM refresh_token WHERE family_id = ?"
                    + " AND revoked = false", Long.class, family);
            // 409: the loser lost the version race and the winner's session lives on
            // 401: the loser read the spent token and revoked the family, the winner's session with it
            assertThat(live).isEqualTo(statuses.contains(409) ? 1 : 0);
        }
    }

    /** Waits until a statement on refresh_token waits on a lock: the revocation, behind the rotation. */
    private static void awaitALockWait(JdbcTemplate owner) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (owner.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname = current_database()"
                + " AND wait_event_type = 'Lock' AND query LIKE '%refresh_token%FOR UPDATE%'", Long.class) == 0) {
            assertThat(System.nanoTime()).as("the revocation waits for the rotation").isLessThan(deadline);
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(50));
        }
    }
}
