package vn.essvn.erpcafe.identity.security;

import java.security.Principal;
import java.util.Set;
import java.util.UUID;

/**
 * The authenticated principal placed in the security context. Carries identity
 * plus the set of branches the user may act in. {@link #getName()} returns the
 * email, which is what JPA auditing records as created/updated-by.
 */
public record AuthPrincipal(
        UUID userId,
        String email,
        UUID companyId,
        Set<UUID> branchIds,
        Set<String> permissions,
        Set<String> roles) implements Principal {

    @Override
    public String getName() {
        return email;
    }

    public boolean canAccessBranch(UUID branchId) {
        return branchIds.contains(branchId);
    }
}
