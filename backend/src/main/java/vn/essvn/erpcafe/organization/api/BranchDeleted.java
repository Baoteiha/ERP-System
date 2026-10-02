package vn.essvn.erpcafe.organization.api;

import java.util.UUID;

/**
 * Class: BranchDeleted
 * Purpose: Announces that a branch was deleted, so other modules can drop access to it.
 */
public record BranchDeleted(UUID branchId, UUID companyId, UUID actorUserId) {
}
