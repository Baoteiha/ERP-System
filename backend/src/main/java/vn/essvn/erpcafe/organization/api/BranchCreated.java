package vn.essvn.erpcafe.organization.api;

import java.util.UUID;

/**
 * Class: BranchCreated
 * Purpose: Announces that a user created a branch, so other modules can give the creator access to it.
 */
public record BranchCreated(UUID branchId, UUID companyId, UUID actorUserId) {
}
