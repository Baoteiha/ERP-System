package vn.essvn.erpcafe.organization.api;

import java.util.Optional;
import java.util.UUID;

/**
 * Published facade of the organization module. Other modules depend on this
 * interface (never on organization's domain/persistence internals).
 */
public interface OrganizationApi {

    /** Resolves a branch by id, if it exists (and is not deleted). */
    Optional<BranchDto> findBranch(UUID branchId);

    /** Whether the given branch exists and is active. */
    boolean branchExists(UUID branchId);
}
