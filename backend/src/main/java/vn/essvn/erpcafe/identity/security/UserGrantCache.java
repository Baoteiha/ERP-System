package vn.essvn.erpcafe.identity.security;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import vn.essvn.erpcafe.identity.domain.UserBranchAccess;
import vn.essvn.erpcafe.identity.persistence.UserBranchAccessRepository;
import vn.essvn.erpcafe.organization.api.BranchDto;
import vn.essvn.erpcafe.organization.api.OrganizationApi;

/**
 * Class: UserGrantCache
 * Purpose: Holds the role each user holds at each live branch, so a token's branch roles are honoured only while still granted.
 */
@Component
public class UserGrantCache {

    /** How long a grant or revoke may take to reach a token on another instance. */
    static final Duration TTL = Duration.ofSeconds(60);

    private final UserBranchAccessRepository accessRepository;
    private final OrganizationApi organizationApi;
    private final ExpiringCache<UUID, Map<UUID, UUID>> cache;

    @Autowired
    public UserGrantCache(UserBranchAccessRepository accessRepository, OrganizationApi organizationApi) {
        this(accessRepository, organizationApi, Clock.systemUTC());
    }

    UserGrantCache(UserBranchAccessRepository accessRepository, OrganizationApi organizationApi, Clock clock) {
        this.accessRepository = accessRepository;
        this.organizationApi = organizationApi;
        this.cache = new ExpiringCache<>(TTL, clock);
    }

    /** The user's role at each live branch, as of at most a minute ago. */
    public Map<UUID, UUID> current(UUID userId, UUID companyId) {
        return cache.get(userId, id -> load(id, companyId));
    }

    /** The user's role at each live branch now; issuing a token reads it so the token and the check agree. */
    public Map<UUID, UUID> reload(UUID userId, UUID companyId) {
        return cache.reload(userId, id -> load(id, companyId));
    }

    /** Forgets the user's grants once the transaction changing them commits. */
    public void evictAfterCommit(UUID userId) {
        cache.evictAfterCommit(userId);
    }

    // a grant on a deleted or deactivated branch grants nothing: not the branch, not its role's permissions
    private Map<UUID, UUID> load(UUID userId, UUID companyId) {
        Set<UUID> liveBranchIds = organizationApi.listActiveBranches(companyId).stream()
                .map(BranchDto::id).collect(Collectors.toSet());
        return accessRepository.findByUserId(userId).stream()
                .filter(a -> liveBranchIds.contains(a.getBranchId()))
                .collect(Collectors.toUnmodifiableMap(UserBranchAccess::getBranchId, UserBranchAccess::getRoleId));
    }
}
