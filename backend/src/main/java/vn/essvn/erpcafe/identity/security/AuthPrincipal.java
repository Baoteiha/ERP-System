package vn.essvn.erpcafe.identity.security;

import java.security.Principal;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The authenticated principal placed in the security context. Carries identity
 * plus the permissions the user holds at each branch they may act in, and those
 * in force for this request (ADR-0007). {@link #getName()} returns the email,
 * which is what JPA auditing records as created/updated-by.
 *
 * @param permissionsByBranch every branch the user holds, with the permissions of their role there
 * @param permissions         in force for this request: the active branch's (every branch's when the
 *                            request names none), plus company-level ones held at any branch
 * @param roles               the names of the roles the user holds, at any branch
 */
public record AuthPrincipal(
        UUID userId,
        String email,
        UUID companyId,
        Map<UUID, Set<String>> permissionsByBranch,
        Set<String> permissions,
        Set<String> roles) implements Principal {

    @Override
    public String getName() {
        return email;
    }

    /** Every branch the user holds, whichever branch this request acts in. */
    public Set<UUID> branchIds() {
        return permissionsByBranch.keySet();
    }

    /** Every permission held at any branch: the full-grants view company-level administration weighs. */
    public Set<String> allPermissions() {
        return permissionsByBranch.values().stream().flatMap(Set::stream).collect(Collectors.toSet());
    }

    public Set<String> permissionsAt(UUID branchId) {
        return permissionsByBranch.getOrDefault(branchId, Set.of());
    }

    public boolean canAccessBranch(UUID branchId) {
        return permissionsByBranch.containsKey(branchId);
    }
}
