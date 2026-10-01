package vn.essvn.erpcafe.identity.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import vn.essvn.erpcafe.identity.domain.RefreshToken;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /** Outstanding tokens for a user — revoked in bulk when their credentials or status change. */
    List<RefreshToken> findByUserIdAndRevokedFalse(UUID userId);

    /** Live tokens descended from one login — revoked together when a replay is detected. */
    List<RefreshToken> findByFamilyIdAndRevokedFalse(UUID familyId);

    /** Bulk delete used by the daily purge; returns how many rows went. */
    @Modifying
    @Query("delete from RefreshToken t where t.expiresAt < :cutoff")
    int deleteByExpiresAtBefore(@Param("cutoff") Instant cutoff);
}
