package vn.essvn.erpcafe.common.domain;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;

/**
 * Base class for entities that belong to a single branch. The {@code branchId}
 * discriminator is the backbone of multi-branch data scoping; branch-scoped
 * modules (catalog, inventory, sales, staff) extend this from Phase 2 onward.
 */
@MappedSuperclass
public abstract class BranchScopedEntity extends AuditableEntity {

    @Column(name = "branch_id", nullable = false)
    private UUID branchId;

    public UUID getBranchId() {
        return branchId;
    }

    public void setBranchId(UUID branchId) {
        this.branchId = branchId;
    }
}
