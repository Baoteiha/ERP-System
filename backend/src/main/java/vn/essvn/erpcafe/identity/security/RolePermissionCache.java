package vn.essvn.erpcafe.identity.security;

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import vn.essvn.erpcafe.identity.domain.Permission;
import vn.essvn.erpcafe.identity.persistence.RoleRepository;

/**
 * Class: RolePermissionCache
 * Purpose: Resolves the roles an access token names into their names and permissions, reading each role at most once a minute.
 */
@Component
public class RolePermissionCache {

    /** How stale a role edit may be on another instance, or after an edit that bypassed {@link #evictAfterCommit}. */
    static final Duration TTL = Duration.ofSeconds(60);

    /**
     * Class: RoleGrant
     * Purpose: A role's name and permissions as request authorization weighs them.
     */
    public record RoleGrant(String name, Set<String> permissions) {
    }

    private final RoleRepository roleRepository;
    private final ExpiringCache<UUID, Optional<RoleGrant>> cache;

    @Autowired
    public RolePermissionCache(RoleRepository roleRepository) {
        this(roleRepository, Clock.systemUTC());
    }

    RolePermissionCache(RoleRepository roleRepository, Clock clock) {
        this.roleRepository = roleRepository;
        this.cache = new ExpiringCache<>(TTL, clock);
    }

    /** The role's name and permissions; empty when the role no longer exists. */
    public Optional<RoleGrant> get(UUID roleId) {
        return cache.get(roleId, this::load);
    }

    /** Forgets the role once the transaction changing it commits, so the next request reads the change. */
    public void evictAfterCommit(UUID roleId) {
        cache.evictAfterCommit(roleId);
    }

    private Optional<RoleGrant> load(UUID roleId) {
        return roleRepository.findById(roleId).map(role -> new RoleGrant(role.getName(),
                role.getPermissions().stream().map(Permission::getName).collect(Collectors.toUnmodifiableSet())));
    }
}
