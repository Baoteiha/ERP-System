package vn.essvn.erpcafe.identity.application;

import java.util.Collection;
import java.util.UUID;

import org.springframework.stereotype.Component;

import vn.essvn.erpcafe.identity.persistence.UserRepository;
import vn.essvn.erpcafe.identity.security.UserGrantCache;

/**
 * Class: AuthVersions
 * Purpose: Ends the access tokens users hold by moving their auth version past the one each token carries.
 *
 * <p>Used for what the grant and role caches cannot follow: a status change, a password change or
 * reset, and a branch closing. A grant, a revoke or a role edit reaches issued tokens through those
 * caches and leaves the token itself valid (ADR-0007).
 */
@Component
class AuthVersions {

    private final UserRepository userRepository;
    private final UserGrantCache grantCache;

    AuthVersions(UserRepository userRepository, UserGrantCache grantCache) {
        this.userRepository = userRepository;
        this.grantCache = grantCache;
    }

    /** In the caller's transaction; this instance sees it once that commits, another within the cache's TTL. */
    void bump(UUID companyId, Collection<UUID> userIds) {
        if (userIds.isEmpty()) {
            return;
        }
        userRepository.bumpAuthVersion(userIds, companyId);
        userIds.forEach(grantCache::evictAfterCommit);
    }
}
