package vn.essvn.erpcafe.identity.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import vn.essvn.erpcafe.identity.domain.RefreshToken;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /** Outstanding tokens for a user — revoked in bulk when their credentials or status change. */
    List<RefreshToken> findByUserIdAndRevokedFalse(UUID userId);
}
