package vn.essvn.erpcafe.identity.application;

import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import vn.essvn.erpcafe.identity.domain.RefreshToken;
import vn.essvn.erpcafe.identity.persistence.RefreshTokenRepository;

/**
 * Revokes a whole refresh-token family in its own transaction.
 *
 * <p>This exists as a separate bean on purpose. Reuse detection revokes the family
 * and then throws, and a thrown {@code RuntimeException} rolls the caller's
 * transaction back — which would undo the very revocation that makes the defence
 * work. {@code REQUIRES_NEW} suspends the caller's transaction and commits this one
 * independently, so the family stays dead even though the request fails.
 *
 * <p>It must be a distinct bean rather than a method on {@code AuthService}:
 * Spring's transactional proxying does not apply to self-invocation, so an
 * annotated private or sibling method would silently join the outer transaction
 * and reintroduce the bug.
 */
@Component
public class RefreshTokenFamilyRevoker {

    private final RefreshTokenRepository refreshTokenRepository;

    public RefreshTokenFamilyRevoker(RefreshTokenRepository refreshTokenRepository) {
        this.refreshTokenRepository = refreshTokenRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void revokeFamily(UUID familyId) {
        refreshTokenRepository.findByFamilyIdAndRevokedFalse(familyId).forEach(RefreshToken::revoke);
    }
}
