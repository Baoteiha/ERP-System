package vn.essvn.erpcafe.identity.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import vn.essvn.erpcafe.identity.domain.RefreshToken;

@Transactional(readOnly = true)
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /** Outstanding tokens for a user — revoked in bulk when their credentials or status change. */
    List<RefreshToken> findByUserIdAndRevokedFalse(UUID userId);

    /**
     * Revokes every live token descended from one login, when a replay is detected. One statement, so
     * replays racing each other each revoke whatever is still live and none trips over another's version.
     */
    @Transactional
    @Modifying
    @Query("update RefreshToken t set t.revoked = true, t.version = t.version + 1"
            + " where t.familyId = :familyId and t.revoked = false")
    int revokeFamily(@Param("familyId") UUID familyId);

    /** Bulk delete used by the daily purge; returns how many rows went. */
    @Transactional
    @Modifying
    @Query("delete from RefreshToken t where t.expiresAt < :cutoff")
    int deleteByExpiresAtBefore(@Param("cutoff") Instant cutoff);
}
