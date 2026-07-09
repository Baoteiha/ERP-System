package vn.essvn.erpcafe.organization.api;

import java.util.UUID;

/**
 * Published, JPA-free view of a branch for other modules to resolve branch
 * references. Part of the organization module's public API.
 */
public record BranchDto(
        UUID id,
        UUID companyId,
        String name,
        String code,
        boolean active) {
}
