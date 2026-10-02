package vn.essvn.erpcafe.identity.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import vn.essvn.erpcafe.identity.domain.UserBranchAccess;
import vn.essvn.erpcafe.identity.persistence.UserBranchAccessRepository;
import vn.essvn.erpcafe.identity.persistence.UserRepository;
import vn.essvn.erpcafe.identity.security.UserGrantCache.Grants;
import vn.essvn.erpcafe.organization.api.BranchDto;
import vn.essvn.erpcafe.organization.api.OrganizationApi;

/**
 * Class: UserGrantCacheTest
 * Purpose: Pins the grant cache to live branches only, one read per user per TTL, and no read kept past an eviction.
 */
class UserGrantCacheTest {

    private final UUID userId = UUID.randomUUID();
    private final UUID companyId = UUID.randomUUID();
    private final UUID live = UUID.randomUUID();
    private final UUID closed = UUID.randomUUID();
    private final UUID roleId = UUID.randomUUID();
    private final UserRepository users = mock(UserRepository.class);
    private final UserBranchAccessRepository grants = mock(UserBranchAccessRepository.class);
    private final OrganizationApi organization = mock(OrganizationApi.class);
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
    private final UserGrantCache cache = new UserGrantCache(users, grants, organization, clock);

    private void branchesAreLive() {
        when(organization.listActiveBranches(companyId)).thenReturn(List.of(
                new BranchDto(live, companyId, "Live", "L", true, BigDecimal.ZERO)));
        when(users.findAuthVersion(userId, companyId)).thenReturn(Optional.of(4));
    }

    @Test
    void keepsOnlyGrantsOnLiveBranches_andReadsOnceAMinute() {
        branchesAreLive();
        when(grants.findByUserId(userId)).thenReturn(List.of(
                new UserBranchAccess(userId, live, roleId), new UserBranchAccess(userId, closed, roleId)));

        assertThat(cache.current(userId, companyId)).contains(new Grants(4, Map.of(live, roleId)));
        now = now.plus(UserGrantCache.TTL).minusSeconds(1);
        cache.current(userId, companyId);

        verify(grants, times(1)).findByUserId(userId);
        now = now.plusSeconds(1);
        cache.current(userId, companyId);
        verify(grants, times(2)).findByUserId(userId);
    }

    @Test
    void aReadThatRacedAnEviction_isNotKept() {
        branchesAreLive();
        // the revoke commits, and its eviction runs, while the first read is still on its way back
        when(grants.findByUserId(userId)).thenAnswer(inv -> {
            cache.evictAfterCommit(userId);
            return List.of(new UserBranchAccess(userId, live, roleId));
        }).thenReturn(List.of());

        cache.current(userId, companyId);

        assertThat(cache.current(userId, companyId)).map(Grants::roleByBranch).contains(Map.of());
    }

    @Test
    void aUserNoLongerThere_hasNoGrants_andIsNotReadAgainWithinTheTtl() {
        when(users.findAuthVersion(userId, companyId)).thenReturn(Optional.empty());

        assertThat(cache.current(userId, companyId)).isEmpty();
        cache.current(userId, companyId);

        verify(users, times(1)).findAuthVersion(userId, companyId);
    }

    @Test
    void reload_readsTheAuthVersionAfresh_evenWithinTheTtl() {
        branchesAreLive();
        when(grants.findByUserId(userId)).thenReturn(List.of(new UserBranchAccess(userId, live, roleId)));
        cache.current(userId, companyId);
        when(users.findAuthVersion(userId, companyId)).thenReturn(Optional.of(5));

        assertThat(cache.current(userId, companyId)).map(Grants::authVersion).contains(4);
        assertThat(cache.reload(userId, companyId)).map(Grants::authVersion).contains(5);
        assertThat(cache.current(userId, companyId)).map(Grants::authVersion).contains(5);
    }
}
