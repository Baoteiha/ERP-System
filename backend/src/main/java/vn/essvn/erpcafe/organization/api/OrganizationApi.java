package vn.essvn.erpcafe.organization.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Published facade of the organization module. Other modules depend on this
 * interface (never on organization's domain/persistence internals).
 */
public interface OrganizationApi {

    /** Resolves a branch of {@code companyId} by id; another company's branch is empty, like a missing one. */
    Optional<BranchDto> findBranch(UUID companyId, UUID branchId);

    /**
     * The company that owns the given branch. Empty when the branch does not exist, belongs to
     * another company than the caller's (the tenant seam hides it), or when no tenant is set.
     */
    Optional<UUID> companyIdOf(UUID branchId);

    /** Whether the given branch exists, is active, and belongs to {@code companyId}. */
    boolean branchExistsInCompany(UUID branchId, UUID companyId);

    /** A company's active branches — used by reporting for cross-branch comparison. */
    List<BranchDto> listActiveBranches(UUID companyId);
}
