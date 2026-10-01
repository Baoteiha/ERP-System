package vn.essvn.erpcafe.identity.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import vn.essvn.erpcafe.identity.domain.Permission;
import vn.essvn.erpcafe.identity.domain.Role;
import vn.essvn.erpcafe.identity.persistence.RoleRepository;
import vn.essvn.erpcafe.identity.security.RolePermissionCache.RoleGrant;

/**
 * Class: RolePermissionCacheTest
 * Purpose: Pins the role cache to one read per role a minute, a fresh read after an eviction, and nothing for a missing role.
 */
class RolePermissionCacheTest {

    private final UUID roleId = UUID.randomUUID();
    private final RoleRepository roles = mock(RoleRepository.class);
    private Instant now = Instant.parse("2026-10-01T08:00:00Z");
    private final Clock clock = new Clock() {
        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    };
    private final RolePermissionCache cache = new RolePermissionCache(roles, clock);

    private void roleHolds(String... permissions) {
        Role role = new Role("CASHIER", "d");
        for (String name : permissions) {
            role.getPermissions().add(new Permission(name, name));
        }
        when(roles.findById(roleId)).thenReturn(Optional.of(role));
    }

    @Test
    void readsARoleOnceWithinTheMinute() {
        roleHolds("sales:write");

        cache.get(roleId);
        now = now.plus(RolePermissionCache.TTL).minusSeconds(1);
        Optional<RoleGrant> grant = cache.get(roleId);

        assertThat(grant).contains(new RoleGrant("CASHIER", Set.of("sales:write")));
        verify(roles, times(1)).findById(roleId);
    }

    @Test
    void rereadsARoleOnceTheMinuteIsUp() {
        roleHolds("sales:write");
        cache.get(roleId);

        roleHolds("sales:write", "sales:refund");
        now = now.plus(RolePermissionCache.TTL);

        assertThat(cache.get(roleId)).hasValueSatisfying(g -> assertThat(g.permissions()).contains("sales:refund"));
    }

    @Test
    void rereadsARoleAtOnceAfterItIsEvicted() {
        roleHolds("sales:write");
        cache.get(roleId);

        roleHolds();
        cache.evictAfterCommit(roleId);
        now = now.plus(Duration.ofSeconds(1));

        assertThat(cache.get(roleId)).hasValueSatisfying(g -> assertThat(g.permissions()).isEmpty());
    }

    @Test
    void aReadThatRacedAnEviction_isNotKept() {
        Role before = new Role("CASHIER", "d");
        Role after = new Role("CASHIER", "d");
        after.getPermissions().add(new Permission("sales:refund", "sales:refund"));
        // the edit commits, and its eviction runs, while the first read is still on its way back
        when(roles.findById(roleId)).thenAnswer(inv -> {
            cache.evictAfterCommit(roleId);
            return Optional.of(before);
        }).thenReturn(Optional.of(after));

        cache.get(roleId);

        assertThat(cache.get(roleId)).hasValueSatisfying(g -> assertThat(g.permissions()).contains("sales:refund"));
    }

    @Test
    void aMissingRoleGrantsNothing() {
        when(roles.findById(roleId)).thenReturn(Optional.empty());

        assertThat(cache.get(roleId)).isEmpty();
    }
}
