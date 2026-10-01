package vn.essvn.erpcafe.staff.application;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;

import vn.essvn.erpcafe.common.exception.TooManyRequestsException;

/**
 * Class: PinAttemptService
 * Purpose: Locks PIN clocking for an employee for 15 minutes after 5 wrong PINs within 15 minutes.
 *
 * State is in-memory and per instance (like the login throttle): it resets on restart and is not shared
 * across replicas, until the DB-backed throttle replaces it. The map is bounded, evicting the oldest employee.
 */
@Service
public class PinAttemptService {

    static final int MAX_FAILURES = 5;
    static final Duration WINDOW = Duration.ofMinutes(15);
    private static final int MAX_TRACKED = 10_000;

    private record Attempts(int failures, Instant windowStart, Instant lockedUntil) {
    }

    private final Map<UUID, Attempts> attempts = new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<UUID, Attempts> eldest) {
            return size() > MAX_TRACKED;
        }
    };

    /** Throws 429 while the employee is locked, before any PIN is compared. */
    public synchronized void checkNotLocked(UUID employeeId) {
        Attempts current = attempts.get(employeeId);
        if (current == null) {
            return;
        }
        Instant now = Instant.now();
        if (current.lockedUntil() != null && now.isBefore(current.lockedUntil())) {
            throw new TooManyRequestsException("Too many wrong PINs. Try again later.");
        }
        if (current.lockedUntil() != null || now.isAfter(current.windowStart().plus(WINDOW))) {
            attempts.remove(employeeId);
        }
    }

    public synchronized void recordFailure(UUID employeeId) {
        Instant now = Instant.now();
        attempts.compute(employeeId, (id, existing) -> {
            int failures = existing == null || now.isAfter(existing.windowStart().plus(WINDOW))
                    ? 1 : existing.failures() + 1;
            Instant start = failures == 1 ? now : existing.windowStart();
            return new Attempts(failures, start, failures >= MAX_FAILURES ? now.plus(WINDOW) : null);
        });
    }

    public synchronized void reset(UUID employeeId) {
        attempts.remove(employeeId);
    }
}
