package vn.essvn.erpcafe.identity.domain;

import java.util.Locale;

import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import vn.essvn.erpcafe.common.domain.TenantOwnedEntity;

/**
 * A login account, belonging to a company. A user's operational access is
 * granted per branch via {@link UserBranchAccess} (not stored here). Soft-deletable.
 */
@Entity
@Table(name = "app_user")
@SQLDelete(sql = "UPDATE app_user SET deleted = true WHERE id = ? AND version = ?")
@SQLRestriction("deleted = false")
public class User extends TenantOwnedEntity {

    @Column(name = "email", nullable = false)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "full_name")
    private String fullName;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private UserStatus status = UserStatus.ACTIVE;

    @Column(name = "deleted", nullable = false)
    private boolean deleted = false;

    protected User() {
        // for JPA
    }

    public User(String email, String passwordHash, String fullName) {
        this.email = email;
        this.passwordHash = passwordHash;
        this.fullName = fullName;
    }

    /** Emails are compared case-insensitively, so they are stored trimmed and lower-case. */
    public static String normalizeEmail(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public String getFullName() {
        return fullName;
    }

    public void setFullName(String fullName) {
        this.fullName = fullName;
    }

    public UserStatus getStatus() {
        return status;
    }

    public void setStatus(UserStatus status) {
        this.status = status;
    }

    public boolean isActive() {
        return status == UserStatus.ACTIVE;
    }
}
