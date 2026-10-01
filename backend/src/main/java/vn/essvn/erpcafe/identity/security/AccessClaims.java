package vn.essvn.erpcafe.identity.security;

import java.util.Map;
import java.util.UUID;

/**
 * Class: AccessClaims
 * Purpose: What an access token asserts: who the user is and which role they hold at each live branch.
 */
public record AccessClaims(UUID userId, String email, UUID companyId, Map<UUID, UUID> roleByBranch) {

    public AccessClaims {
        roleByBranch = Map.copyOf(roleByBranch);
    }
}
