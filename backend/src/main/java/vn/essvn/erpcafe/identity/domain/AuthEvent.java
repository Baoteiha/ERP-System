package vn.essvn.erpcafe.identity.domain;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Class: AuthEvent
 * Purpose: One entry of a company's authentication audit trail, as it is read back.
 *
 * @param userId the user the event is about; null for a failed sign-in naming no account
 * @param ip     the client address, as the login throttle sees it; null outside a request
 * @param detail what else the event records, such as the branch and role of a grant or the acting user
 */
public record AuthEvent(UUID id, UUID userId, AuthEventType type, String ip, Instant occurredAt,
        Map<String, Object> detail) {
}
