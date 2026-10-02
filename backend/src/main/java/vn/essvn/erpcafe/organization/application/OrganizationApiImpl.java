package vn.essvn.erpcafe.organization.application;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.essvn.erpcafe.organization.api.BranchDto;
import vn.essvn.erpcafe.organization.api.OrganizationApi;
import vn.essvn.erpcafe.organization.domain.Branch;
import vn.essvn.erpcafe.organization.mapper.BranchMapper;
import vn.essvn.erpcafe.organization.persistence.BranchRepository;

/**
 * Class: OrganizationApiImpl
 * Purpose: Implements the published OrganizationApi over the branch repository.
 */
@Service
@Transactional(readOnly = true)
public class OrganizationApiImpl implements OrganizationApi {

    // These take an explicit companyId and are deliberately not scoped through the caller's identity:
    // callers are other modules that already pass the caller's own company, plus login, which reads as
    // the system across every company. Inside a tenant the seam has already narrowed the rows to that
    // company; the companyId still decides, so the answer is the same in either scope.

    private final BranchRepository branchRepository;
    private final BranchMapper branchMapper;

    public OrganizationApiImpl(BranchRepository branchRepository, BranchMapper branchMapper) {
        this.branchRepository = branchRepository;
        this.branchMapper = branchMapper;
    }

    @Override
    public Optional<BranchDto> findBranch(UUID companyId, UUID branchId) {
        return branchRepository.findById(branchId)
                .filter(b -> b.getCompanyId().equals(companyId))
                .map(branchMapper::toDto);
    }

    @Override
    public Optional<UUID> companyIdOf(UUID branchId) {
        return branchRepository.findById(branchId).map(Branch::getCompanyId);
    }

    @Override
    public boolean branchExistsInCompany(UUID companyId, UUID branchId) {
        return branchRepository.findById(branchId)
                .filter(b -> b.isActive() && b.getCompanyId().equals(companyId))
                .isPresent();
    }

    @Override
    public List<BranchDto> listActiveBranches(UUID companyId) {
        return branchRepository.findByCompanyIdAndActiveTrue(companyId).stream()
                .map(branchMapper::toDto)
                .toList();
    }
}
