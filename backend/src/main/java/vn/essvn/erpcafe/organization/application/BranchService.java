package vn.essvn.erpcafe.organization.application;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.essvn.erpcafe.common.exception.ConflictException;
import vn.essvn.erpcafe.common.exception.ResourceNotFoundException;
import vn.essvn.erpcafe.organization.api.BranchDto;
import vn.essvn.erpcafe.organization.api.OrganizationApi;
import vn.essvn.erpcafe.organization.domain.Branch;
import vn.essvn.erpcafe.organization.mapper.BranchMapper;
import vn.essvn.erpcafe.organization.persistence.BranchRepository;
import vn.essvn.erpcafe.organization.persistence.CompanyRepository;
import vn.essvn.erpcafe.organization.web.BranchRequest;

@Service
@Transactional
public class BranchService implements OrganizationApi {

    private final BranchRepository branchRepository;
    private final CompanyRepository companyRepository;
    private final BranchMapper branchMapper;

    public BranchService(BranchRepository branchRepository, CompanyRepository companyRepository,
            BranchMapper branchMapper) {
        this.branchRepository = branchRepository;
        this.companyRepository = companyRepository;
        this.branchMapper = branchMapper;
    }

    @Transactional(readOnly = true)
    public Page<Branch> list(Pageable pageable) {
        return branchRepository.findAll(pageable);
    }

    @Transactional(readOnly = true)
    public Branch get(UUID id) {
        return branchRepository.findById(id)
                .orElseThrow(() -> ResourceNotFoundException.of("Branch", id));
    }

    public Branch create(BranchRequest request) {
        requireCompany(request.companyId());
        if (branchRepository.existsByCompanyIdAndCode(request.companyId(), request.code())) {
            throw new ConflictException("Branch code already exists in company: " + request.code());
        }
        Branch branch = new Branch(request.companyId(), request.name(), request.code());
        apply(branch, request);
        return branchRepository.save(branch);
    }

    public Branch update(UUID id, BranchRequest request) {
        Branch branch = get(id);
        requireCompany(request.companyId());
        boolean codeOrCompanyChanged = !branch.getCode().equals(request.code())
                || !branch.getCompanyId().equals(request.companyId());
        if (codeOrCompanyChanged
                && branchRepository.existsByCompanyIdAndCode(request.companyId(), request.code())) {
            throw new ConflictException("Branch code already exists in company: " + request.code());
        }
        branch.setCompanyId(request.companyId());
        branch.setCode(request.code());
        apply(branch, request);
        return branch;
    }

    public void delete(UUID id) {
        branchRepository.delete(get(id));
    }

    // --- OrganizationApi (published to other modules) ---

    @Override
    @Transactional(readOnly = true)
    public Optional<BranchDto> findBranch(UUID branchId) {
        return branchRepository.findById(branchId).map(branchMapper::toDto);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean branchExists(UUID branchId) {
        return branchRepository.findById(branchId).map(Branch::isActive).orElse(false);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean branchExistsInCompany(UUID branchId, UUID companyId) {
        return branchRepository.existsByIdAndCompanyIdAndActiveTrue(branchId, companyId);
    }

    @Override
    @Transactional(readOnly = true)
    public java.util.List<BranchDto> listActiveBranches(UUID companyId) {
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

    private void requireCompany(UUID companyId) {
        if (!companyRepository.existsById(companyId)) {
            throw ResourceNotFoundException.of("Company", companyId);
        }
    }
}
