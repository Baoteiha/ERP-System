package vn.essvn.erpcafe.organization.application;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.essvn.erpcafe.common.exception.ConflictException;
import vn.essvn.erpcafe.common.exception.ResourceNotFoundException;
import vn.essvn.erpcafe.identity.security.CurrentUser;
import vn.essvn.erpcafe.organization.api.BranchDto;
import vn.essvn.erpcafe.organization.api.OrganizationApi;
import vn.essvn.erpcafe.organization.domain.Branch;
import vn.essvn.erpcafe.organization.mapper.BranchMapper;
import vn.essvn.erpcafe.organization.persistence.BranchRepository;
import vn.essvn.erpcafe.organization.web.BranchRequest;

@Service
@Transactional
public class BranchService implements OrganizationApi {

    private final BranchRepository branchRepository;
    private final BranchMapper branchMapper;
    private final CurrentUser currentUser;

    public BranchService(BranchRepository branchRepository, BranchMapper branchMapper,
            CurrentUser currentUser) {
        this.branchRepository = branchRepository;
        this.branchMapper = branchMapper;
        this.currentUser = currentUser;
    }

    @Transactional(readOnly = true)
    public Page<Branch> list(Pageable pageable) {
        return branchRepository.findByCompanyId(currentUser.require().companyId(), pageable);
    }

    /** Resolves a branch within the caller's company; another tenant's id is a 404. */
    @Transactional(readOnly = true)
    public Branch get(UUID id) {
        return branchRepository.findByIdAndCompanyId(id, currentUser.require().companyId())
                .orElseThrow(() -> ResourceNotFoundException.of("Branch", id));
    }

    public Branch create(BranchRequest request) {
        UUID companyId = currentUser.require().companyId();
        if (branchRepository.existsByCompanyIdAndCode(companyId, request.code())) {
            throw new ConflictException("Branch code already exists in company: " + request.code());
        }
        Branch branch = new Branch(companyId, request.name(), request.code());
        apply(branch, request);
        return branchRepository.save(branch);
    }

    /**
     * Updates a branch in the caller's company. The owning company is deliberately
     * not touched: moving a branch between tenants would hand every branch-scoped
     * query downstream to the wrong company.
     */
    public Branch update(UUID id, BranchRequest request) {
        Branch branch = get(id);
        if (!branch.getCode().equals(request.code())
                && branchRepository.existsByCompanyIdAndCode(branch.getCompanyId(), request.code())) {
            throw new ConflictException("Branch code already exists in company: " + request.code());
        }
        branch.setCode(request.code());
        apply(branch, request);
        return branch;
    }

    public void delete(UUID id) {
        branchRepository.delete(get(id));
    }

    // --- OrganizationApi (published to other modules) ---
    //
    // These take an explicit companyId and are deliberately not scoped through
    // CurrentUser: callers are other modules that already pass the caller's own
    // company, plus DataInitializer, which runs with no security context.

    @Override
    @Transactional(readOnly = true)
    public Optional<BranchDto> findBranch(UUID branchId) {
        return branchRepository.findById(branchId).map(branchMapper::toDto);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UUID> companyIdOf(UUID branchId) {
        return branchRepository.findById(branchId).map(Branch::getCompanyId);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean branchExistsInCompany(UUID branchId, UUID companyId) {
        return branchRepository.existsByIdAndCompanyIdAndActiveTrue(branchId, companyId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<BranchDto> listActiveBranches(UUID companyId) {
        return branchRepository.findByCompanyIdAndActiveTrue(companyId).stream()
                .map(branchMapper::toDto)
                .toList();
    }

    private void apply(Branch branch, BranchRequest request) {
        branch.setName(request.name());
        branch.setAddress(request.address());
        branch.setPhone(request.phone());
        if (request.active() != null) {
            branch.setActive(request.active());
        }
    }
}
