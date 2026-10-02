package vn.essvn.erpcafe.identity.domain;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Class: AuthEvent
 * Purpose: One entry of a company's authentication audit trail, as it is read back.
 */
public record AuthEvent(UUID id, UUID userId, AuthEventType type, String ip, Instant occurredAt,
        Map<String, Object> detail) {
    // userId: null for a failed sign-in naming no account. ip: the client address as the login throttle sees
    // it, null outside a request. detail: what else the event records, such as a grant's branch and role or the
    // acting user.
}
