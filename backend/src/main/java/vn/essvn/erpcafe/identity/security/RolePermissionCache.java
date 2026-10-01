package vn.essvn.erpcafe.identity.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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

    private record Entry(Optional<RoleGrant> grant, Instant loadedAt) {
    }

    private final RoleRepository roleRepository;
    private final Clock clock;
    private final ConcurrentMap<UUID, Entry> entries = new ConcurrentHashMap<>();

    @Autowired
    public RolePermissionCache(RoleRepository roleRepository) {
        this(roleRepository, Clock.systemUTC());
    }

    RolePermissionCache(RoleRepository roleRepository, Clock clock) {
        this.roleRepository = roleRepository;
        this.clock = clock;
    }

    /** The role's name and permissions; empty when the role no longer exists. */
    public Optional<RoleGrant> get(UUID roleId) {
        Instant now = clock.instant();
        Entry entry = entries.get(roleId);
        if (entry == null || !now.isBefore(entry.loadedAt().plus(TTL))) {
            entry = new Entry(load(roleId), now);
            entries.put(roleId, entry);
        }
        return entry.grant();
    }

    /** Forgets the role once the transaction changing it commits, so the next request reads the change. */
    public void evictAfterCommit(UUID roleId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            entries.remove(roleId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                entries.remove(roleId);
            }
        });
    }

    private Optional<RoleGrant> load(UUID roleId) {
        return roleRepository.findById(roleId).map(role -> new RoleGrant(role.getName(),
                role.getPermissions().stream().map(Permission::getName).collect(Collectors.toUnmodifiableSet())));
    }
}
