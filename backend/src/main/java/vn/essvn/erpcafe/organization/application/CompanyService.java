package vn.essvn.erpcafe.organization.application;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.essvn.erpcafe.common.context.TenantContext;
import vn.essvn.erpcafe.common.exception.BusinessRuleException;
import vn.essvn.erpcafe.common.exception.ConflictException;
import vn.essvn.erpcafe.common.exception.ResourceNotFoundException;
import vn.essvn.erpcafe.common.persistence.SystemTransactions;
import vn.essvn.erpcafe.organization.domain.Company;
import vn.essvn.erpcafe.organization.persistence.BranchRepository;
import vn.essvn.erpcafe.organization.persistence.CompanyRepository;
import vn.essvn.erpcafe.organization.web.CompanyRequest;

@Service
@Transactional
public class CompanyService {

    private final CompanyRepository companyRepository;
    private final BranchRepository branchRepository;
    private final SystemTransactions system;

    public CompanyService(CompanyRepository companyRepository, BranchRepository branchRepository,
            SystemTransactions system) {
        this.companyRepository = companyRepository;
        this.branchRepository = branchRepository;
        this.system = system;
    }

    /** A tenant sees exactly one company: their own. */
    @Transactional(readOnly = true)
    public Page<Company> list(Pageable pageable) {
        return companyRepository.findByIdIn(List.of(TenantContext.require()), pageable);
    }

    /**
     * Resolves a company by id, scoped to the caller's own. Another tenant's id is
     * reported as 404 rather than 403, so the endpoint never confirms it exists.
     */
    @Transactional(readOnly = true)
    public Company get(UUID id) {
        if (!id.equals(TenantContext.require())) {
            throw ResourceNotFoundException.of("Company", id);
        }
        return companyRepository.findById(id)
                .orElseThrow(() -> ResourceNotFoundException.of("Company", id));
    }

    public Company update(UUID id, CompanyRequest request) {
        Company company = get(id);
        // codes are unique across companies; a tenant's own connection sees only its company
        if (!company.getCode().equals(request.code())
                && system.run(() -> companyRepository.existsByCode(request.code()))) {
            throw new ConflictException("Company code already exists: " + request.code());
        }
        company.setName(request.name());
        company.setCode(request.code());
        return company;
    }

    /**
     * Deletes the caller's own company. Guarded against orphaning: a company with
     * branches still attached cannot be removed, mirroring the referential-delete
     * guards used in inventory.
     */
    public void delete(UUID id) {
        Company company = get(id);
        if (branchRepository.existsByCompanyId(company.getId())) {
            throw new BusinessRuleException(
                    "Cannot delete a company that still has branches; delete its branches first");
        }
        companyRepository.delete(company);
    }
}
