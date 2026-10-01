package vn.essvn.erpcafe.identity.security;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Service;

import vn.essvn.erpcafe.common.exception.TooManyRequestsException;
import vn.essvn.erpcafe.config.SecurityProperties;

/**
 * Throttles repeated failed logins (AUTH-G11).
 *
 * <p>State is in-memory and per-instance: it resets on restart and is not shared
 * across replicas. That is adequate for the current single-instance deployment and
 * is deliberately cheap; move it behind Redis when the app is scaled out.
 *
 * <p>Two counters run side by side. One is keyed by email <em>and</em> client address:
 * keying on the email alone would let anyone lock a known user out of their own account
 * by failing logins on their behalf. The other is keyed by address alone, so one client
 * cannot spray guesses across many emails. The map is bounded: past the configured
 * number of keys the oldest entry is evicted, so a flood of random emails cannot grow it.
 */
@Service
public class LoginAttemptService {

    private record Window(int failures, Instant firstFailureAt) {
    }

    private final Map<String, Window> windows;

    private final int maxFailures;
    private final int maxFailuresPerAddress;
    private final Duration window;

    public LoginAttemptService(SecurityProperties properties) {
        SecurityProperties.Login login = properties.login();
        this.maxFailures = login.maxFailures();
        this.maxFailuresPerAddress = login.maxFailuresPerAddress();
        this.window = login.window();
        int maxKeys = login.maxTrackedKeys();
        this.windows = new LinkedHashMap<>() {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Window> eldest) {
                return size() > maxKeys;
            }
        };
    }

    /** Throws if the email-and-address pair, or the address alone, is currently blocked. */
    public synchronized void checkNotBlocked(String email, String address) {
        checkNotBlocked(pairKey(email, address), maxFailures);
        checkNotBlocked(addressKey(address), maxFailuresPerAddress);
    }

    /** Records one failed attempt against both counters, starting a fresh window if one lapsed. */
    public synchronized void recordFailure(String email, String address) {
        recordFailure(pairKey(email, address));
        recordFailure(addressKey(address));
    }

    /** Clears the pair's counter after a successful login; the address counter is left to expire. */
    public synchronized void reset(String email, String address) {
        windows.remove(pairKey(email, address));
    }

    private void checkNotBlocked(String key, int limit) {
        Window current = windows.get(key);
        if (current == null) {
            return;
        }
        if (Instant.now().isAfter(current.firstFailureAt().plus(window))) {
            windows.remove(key);
            return;
        }
        if (current.failures() >= limit) {
            throw new TooManyRequestsException("Too many failed login attempts. Try again later.");
        }
    }

    private void recordFailure(String key) {
        Instant now = Instant.now();
        windows.compute(key, (k, existing) -> {
            if (existing == null || now.isAfter(existing.firstFailureAt().plus(window))) {
                return new Window(1, now);
            }
            return new Window(existing.failures() + 1, existing.firstFailureAt());
        });
    }

    private static String pairKey(String email, String address) {
        return email + "|" + address;
    }

    // the prefix cannot collide with a pair key, whose email part is never empty
    private static String addressKey(String address) {
        return "|" + address;
    }
}
