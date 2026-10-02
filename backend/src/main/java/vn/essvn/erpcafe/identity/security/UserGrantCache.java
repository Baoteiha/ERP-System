package vn.essvn.erpcafe.identity.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import vn.essvn.erpcafe.identity.domain.UserBranchAccess;
import vn.essvn.erpcafe.identity.persistence.UserBranchAccessRepository;
import vn.essvn.erpcafe.identity.persistence.UserRepository;
import vn.essvn.erpcafe.organization.api.BranchDto;
import vn.essvn.erpcafe.organization.api.OrganizationApi;

/**
 * Class: UserGrantCache
 * Purpose: Holds each user's auth version and role at each live branch, so a token counts only while both still hold.
 */
@Component
public class UserGrantCache {

    /** How long a grant, revoke or auth-version change may take to reach a token on another instance. */
    static final Duration TTL = Duration.ofSeconds(30);

    /**
     * Class: Grants
     * Purpose: A user's auth version and the role they hold at each live branch, as one read begun at readAt saw them.
     */
    public record Grants(int authVersion, Map<UUID, UUID> roleByBranch, Instant readAt) {
    }

    private final UserRepository userRepository;
    private final UserBranchAccessRepository accessRepository;
    private final OrganizationApi organizationApi;
    private final Clock clock;
    private final ExpiringCache<UUID, Optional<Grants>> cache;

    @Autowired
    public UserGrantCache(UserRepository userRepository, UserBranchAccessRepository accessRepository,
            OrganizationApi organizationApi) {
        this(userRepository, accessRepository, organizationApi, Clock.systemUTC());
    }

    UserGrantCache(UserRepository userRepository, UserBranchAccessRepository accessRepository,
            OrganizationApi organizationApi, Clock clock) {
        this.userRepository = userRepository;
        this.accessRepository = accessRepository;
        this.organizationApi = organizationApi;
        this.clock = clock;
        this.cache = new ExpiringCache<>(TTL, clock);
    }

    /** The user's grants as of at most {@link #TTL} ago; empty once the user is gone. */
    public Optional<Grants> current(UUID userId, UUID companyId) {
        return cache.get(userId, id -> load(id, companyId));
    }

    /** The user's grants now; issuing a token reads them so the token and the check agree. */
    public Optional<Grants> reload(UUID userId, UUID companyId) {
        return cache.reload(userId, id -> load(id, companyId));
    }

    /** Forgets the user's grants once the transaction changing them commits. */
    public void evictAfterCommit(UUID userId) {
        cache.evictAfterCommit(userId);
    }

    // stamped before the reads, so a change they might have missed counts as newer than the copy
    private Optional<Grants> load(UUID userId, UUID companyId) {
        Instant readAt = clock.instant();
        return userRepository.findAuthVersion(userId, companyId)
                .map(version -> new Grants(version, liveGrants(userId, companyId), readAt));
    }

    // a grant on a deleted or deactivated branch grants nothing: not the branch, not its role's permissions
    private Map<UUID, UUID> liveGrants(UUID userId, UUID companyId) {
        Set<UUID> liveBranchIds = organizationApi.listActiveBranches(companyId).stream()
                .map(BranchDto::id).collect(Collectors.toSet());
        return accessRepository.findByUserId(userId).stream()
                .filter(a -> liveBranchIds.contains(a.getBranchId()))
                .collect(Collectors.toUnmodifiableMap(UserBranchAccess::getBranchId, UserBranchAccess::getRoleId));
    }
}
