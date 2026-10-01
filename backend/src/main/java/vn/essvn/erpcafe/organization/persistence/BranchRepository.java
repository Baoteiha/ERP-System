package vn.essvn.erpcafe.organization.persistence;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import vn.essvn.erpcafe.organization.domain.Branch;

/** Branches are tenant-owned: every query here sees only the current company's (see {@code TenantOwnedEntity}). */
public interface BranchRepository extends JpaRepository<Branch, UUID> {

    boolean existsByCode(String code);

    /** Names the company: login reads it as the system, where no tenant filter applies. */
    List<Branch> findByCompanyIdAndActiveTrue(UUID companyId);

    boolean existsByCompanyId(UUID companyId);
}
