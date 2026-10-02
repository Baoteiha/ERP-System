package vn.essvn.erpcafe.identity.application;

import java.util.Collection;
import java.util.UUID;

import org.springframework.stereotype.Component;

import vn.essvn.erpcafe.identity.persistence.UserRepository;
import vn.essvn.erpcafe.identity.security.UserGrantCache;

/**
 * Class: AuthVersions
 * Purpose: Ends the access tokens and open reset links of users whose access changed, by moving their auth version.
 */
@Component
class AuthVersions {

    private final UserRepository userRepository;
    private final UserGrantCache grantCache;
    private final PasswordResetLinks resetLinks;

    AuthVersions(UserRepository userRepository, UserGrantCache grantCache, PasswordResetLinks resetLinks) {
        this.userRepository = userRepository;
        this.grantCache = grantCache;
        this.resetLinks = resetLinks;
    }

    /**
     * For what the grant and role caches cannot follow: a status change, a password change or reset, a branch
     * closing. A grant, a revoke or a role edit reaches issued tokens through those caches and leaves the token
     * valid (ADR-0007). In the caller's transaction; this instance sees it once that commits, another within the
     * cache's TTL.
     */
    void bump(UUID companyId, Collection<UUID> userIds) {
        if (userIds.isEmpty()) {
            return;
        }
        userRepository.bumpAuthVersion(userIds, companyId);
        userIds.forEach(grantCache::evictAfterCommit);
        // a link mailed before the change would otherwise still set a password afterwards
        resetLinks.closeOpen(userIds);
    }
}
