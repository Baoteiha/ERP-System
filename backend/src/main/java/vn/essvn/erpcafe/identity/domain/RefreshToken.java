package vn.essvn.erpcafe.identity.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import vn.essvn.erpcafe.common.domain.BaseEntity;

/**
 * A persisted, revocable refresh token. Only the hash of the opaque token is
 * stored (never the raw value). Rotated on each refresh; revoked on logout.
 */
@Entity
@Table(name = "refresh_token",
        uniqueConstraints = @UniqueConstraint(name = "uk_refresh_token_hash", columnNames = "token_hash"))
public class RefreshToken extends BaseEntity {

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "token_hash", nullable = false, length = 64)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "revoked", nullable = false)
    private boolean revoked = false;

    /**
     * When a rotation replaced this token; null for a live token and for one a logout or an account
     * change revoked. Only a rotated token presented again is a replay (AUTH-103).
     */
    @Column(name = "rotated_at")
    private Instant rotatedAt;

    /**
     * Groups every token descended from one login. Rotation carries the family
     * forward, so detecting a replayed token can revoke all of its siblings.
     */
    @Column(name = "family_id", nullable = false)
    private UUID familyId;

    protected RefreshToken() {
        // for JPA
    }

    public RefreshToken(UUID userId, String tokenHash, Instant expiresAt, UUID familyId) {
        this.userId = userId;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
        this.familyId = familyId;
    }

    public UUID getFamilyId() {
        return familyId;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public boolean isRevoked() {
        return revoked;
    }

    public void revoke() {
        this.revoked = true;
    }

    /** Spends the token on a refresh: revoked, and marked as replaced, so presenting it again is a replay. */
    public void rotate(Instant now) {
        this.revoked = true;
        this.rotatedAt = now;
    }

    public boolean wasRotated() {
        return rotatedAt != null;
    }

    public boolean isActive(Instant now) {
        return !revoked && expiresAt.isAfter(now);
    }
}
