package vn.essvn.erpcafe.identity.security;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import vn.essvn.erpcafe.common.exception.TooManyRequestsException;

/**
 * Throttles repeated failed logins (AUTH-G11).
 *
 * <p>State is in-memory and per-instance: it resets on restart and is not shared
 * across replicas. That is adequate for the current single-instance deployment and
 * is deliberately cheap; move it behind Redis when the app is scaled out.
 *
 * <p>Entries are keyed by email <em>and</em> client address. Keying on the email
 * alone would let anyone lock a known user out of their own account simply by
 * failing logins on their behalf.
 */
@Service
public class LoginAttemptService {

    private record Window(int failures, Instant firstFailureAt) {
    }

    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    private final int maxFailures;
    private final Duration window;

    public LoginAttemptService(
            @Value("${app.security.login.max-failures:10}") int maxFailures,
            @Value("${app.security.login.window:PT15M}") Duration window) {
        this.maxFailures = maxFailures;
        this.window = window;
    }

    /** Throws if the key is currently blocked. Called before the password is checked. */
    public void checkNotBlocked(String key) {
        Window current = windows.get(key);
        if (current == null) {
            return;
        }
        if (Instant.now().isAfter(current.firstFailureAt().plus(window))) {
            windows.remove(key);
            return;
        }
        if (current.failures() >= maxFailures) {
            throw new TooManyRequestsException("Too many failed login attempts. Try again later.");
        }
    }

    /** Records one failed attempt, starting a fresh window if the previous one lapsed. */
    public void recordFailure(String key) {
        Instant now = Instant.now();
        windows.compute(key, (k, existing) -> {
            if (existing == null || now.isAfter(existing.firstFailureAt().plus(window))) {
                return new Window(1, now);
            }
            return new Window(existing.failures() + 1, existing.firstFailureAt());
        });
    }

    /** Clears the counter after a successful login. */
    public void reset(String key) {
        windows.remove(key);
    }
}
