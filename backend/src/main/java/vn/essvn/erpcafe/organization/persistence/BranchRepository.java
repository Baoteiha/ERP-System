package vn.essvn.erpcafe.organization.persistence;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import vn.essvn.erpcafe.organization.domain.Branch;

public interface BranchRepository extends JpaRepository<Branch, UUID> {

    boolean existsByCompanyIdAndCode(UUID companyId, String code);

    boolean existsByIdAndCompanyIdAndActiveTrue(UUID id, UUID companyId);

    List<Branch> findByCompanyIdAndActiveTrue(UUID companyId);
}
