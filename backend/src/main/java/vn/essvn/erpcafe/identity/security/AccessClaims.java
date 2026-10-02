package vn.essvn.erpcafe.identity.security;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Class: AccessClaims
 * Purpose: What an access token asserts: who the user is, their role at each live branch, and their auth version.
 */
public record AccessClaims(UUID userId, String email, UUID companyId, Map<UUID, UUID> roleByBranch, int authVersion,
        Instant issuedAt) {

    public AccessClaims {
        roleByBranch = Map.copyOf(roleByBranch);
    }

    /** Claims to issue: the token is stamped with the time it is signed, to the second. */
    public AccessClaims(UUID userId, String email, UUID companyId, Map<UUID, UUID> roleByBranch, int authVersion) {
        this(userId, email, companyId, roleByBranch, authVersion, null);
    }
}
