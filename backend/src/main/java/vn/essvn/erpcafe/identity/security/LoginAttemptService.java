package vn.essvn.erpcafe.identity.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import vn.essvn.erpcafe.common.exception.TooManyRequestsException;
import vn.essvn.erpcafe.common.persistence.SystemTransactions;
import vn.essvn.erpcafe.config.SecurityProperties;

/**
 * Throttles repeated failed logins (AUTH-G11).
 *
 * <p>The counters live in the {@code login_attempt} table (AUTH-G18), so every instance counts the same
 * failures and a restart forgets none. They are read and written as the schema owner: a login runs
 * before any company is known.
 *
 * <p>Two counters run side by side. One is keyed by email <em>and</em> client address:
 * keying on the email alone would let anyone lock a known user out of their own account
 * by failing logins on their behalf. The other is keyed by address alone, so one client
 * cannot spray guesses across many emails. A counter is stored under the SHA-256 of its key,
 * so whatever is typed as the email, a row stays small and holds no email or address.
 */
@Service
public class LoginAttemptService {

    // Every SET expression reads the row as it was, so both see the old window.
    private static final String RECORD_FAILURES = "INSERT INTO login_attempt AS a (key, window_start, count)"
            + " VALUES (?, ?, 1), (?, ?, 1)"
            + " ON CONFLICT (key) DO UPDATE SET"
            + " count = CASE WHEN a.window_start > ? THEN a.count + 1 ELSE 1 END,"
            + " window_start = CASE WHEN a.window_start > ? THEN a.window_start ELSE EXCLUDED.window_start END";

    private final JdbcTemplate jdbc;
    private final SystemTransactions system;
    private final Clock clock;
    private final int maxFailures;
    private final int maxFailuresPerAddress;
    private final Duration window;

    @Autowired
    public LoginAttemptService(JdbcTemplate jdbc, SystemTransactions system, SecurityProperties properties) {
        this(jdbc, system, properties, Clock.systemUTC());
    }

    LoginAttemptService(JdbcTemplate jdbc, SystemTransactions system, SecurityProperties properties, Clock clock) {
        this.jdbc = jdbc;
        this.system = system;
        this.clock = clock;
        SecurityProperties.Login login = properties.login();
        this.maxFailures = login.maxFailures();
        this.maxFailuresPerAddress = login.maxFailuresPerAddress();
        this.window = login.window();
    }

    /** Throws if the email-and-address pair, or the address alone, is currently blocked. */
    public void checkNotBlocked(String email, String address) {
        String pair = pairKey(email, address);
        String addressOnly = addressKey(address);
        Map<String, Integer> failures = system.run(() -> jdbc.query(
                "SELECT key, count FROM login_attempt WHERE key IN (?, ?) AND window_start > ?",
                rs -> {
                    Map<String, Integer> counts = new HashMap<>();
                    while (rs.next()) {
                        counts.put(rs.getString("key"), rs.getInt("count"));
                    }
                    return counts;
                }, pair, addressOnly, windowCutoff()));
        if (failures.getOrDefault(pair, 0) >= maxFailures
                || failures.getOrDefault(addressOnly, 0) >= maxFailuresPerAddress) {
            throw new TooManyRequestsException("Too many failed login attempts. Try again later.");
        }
    }

    /** Records one failed attempt against both counters, starting a fresh window if one lapsed. */
    public void recordFailure(String email, String address) {
        OffsetDateTime now = now();
        OffsetDateTime cutoff = windowCutoff();
        system.run(() -> jdbc.update(RECORD_FAILURES,
                pairKey(email, address), now, addressKey(address), now, cutoff, cutoff));
    }

    /** Clears the pair's counter after a successful login; the address counter is left to expire. */
    public void reset(String email, String address) {
        system.run(() -> jdbc.update("DELETE FROM login_attempt WHERE key = ?", pairKey(email, address)));
    }

    /** Drops the counters whose window has lapsed, so the table holds only the last window's failures. */
    @Scheduled(cron = "0 */15 * * * *")
    public void purgeLapsed() {
        system.run(() -> jdbc.update("DELETE FROM login_attempt WHERE window_start <= ?", windowCutoff()));
    }

    private OffsetDateTime now() {
        return clock.instant().atOffset(ZoneOffset.UTC);
    }

    // a window that started at or before this has lapsed
    private OffsetDateTime windowCutoff() {
        return now().minus(window);
    }

    private static String pairKey(String email, String address) {
        return sha256(email + "|" + address);
    }

    // the prefix cannot collide with a pair key, whose email part is never empty
    private static String addressKey(String address) {
        return sha256("|" + address);
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}
