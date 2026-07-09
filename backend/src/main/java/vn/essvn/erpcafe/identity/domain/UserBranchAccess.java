package vn.essvn.erpcafe.identity.domain;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import vn.essvn.erpcafe.common.domain.AuditableEntity;

/**
 * Grants a {@link User} a {@link Role} at a specific branch. This is the
 * multi-branch access model: a user can be MANAGER at one branch and CASHIER
 * at another, or a regional manager may hold rows for many branches.
 */
@Entity
@Table(name = "user_branch_access",
        uniqueConstraints = @UniqueConstraint(name = "uk_uba_user_branch", columnNames = {"user_id", "branch_id"}))
public class UserBranchAccess extends AuditableEntity {

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "branch_id", nullable = false)
    private UUID branchId;

    @Column(name = "role_id", nullable = false)
    private UUID roleId;

    protected UserBranchAccess() {
        // for JPA
    }

    public UserBranchAccess(UUID userId, UUID branchId, UUID roleId) {
        this.userId = userId;
        this.branchId = branchId;
        this.roleId = roleId;
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getBranchId() {
        return branchId;
    }

    public UUID getRoleId() {
        return roleId;
    }

    public void setRoleId(UUID roleId) {
        this.roleId = roleId;
    }
}
