package vn.essvn.erpcafe.organization.api;

import java.util.UUID;

/**
 * Class: BranchDeactivated
 * Purpose: Announces that a branch stopped being active, so other modules can drop access to it.
 */
public record BranchDeactivated(UUID branchId, UUID companyId, UUID actorUserId) {
}
