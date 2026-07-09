package vn.essvn.erpcafe.organization.application;

import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.essvn.erpcafe.common.exception.ConflictException;
import vn.essvn.erpcafe.common.exception.ResourceNotFoundException;
import vn.essvn.erpcafe.organization.domain.Company;
import vn.essvn.erpcafe.organization.persistence.CompanyRepository;
import vn.essvn.erpcafe.organization.web.CompanyRequest;

@Service
@Transactional
public class CompanyService {

    private final CompanyRepository companyRepository;

    public CompanyService(CompanyRepository companyRepository) {
        this.companyRepository = companyRepository;
    }

    @Transactional(readOnly = true)
    public Page<Company> list(Pageable pageable) {
        return companyRepository.findAll(pageable);
    }

    @Transactional(readOnly = true)
    public Company get(UUID id) {
        return companyRepository.findById(id)
                .orElseThrow(() -> ResourceNotFoundException.of("Company", id));
    }

    public Company create(CompanyRequest request) {
        if (companyRepository.existsByCode(request.code())) {
            throw new ConflictException("Company code already exists: " + request.code());
        }
        return companyRepository.save(new Company(request.name(), request.code()));
    }

    public Company update(UUID id, CompanyRequest request) {
        Company company = get(id);
        if (!company.getCode().equals(request.code()) && companyRepository.existsByCode(request.code())) {
            throw new ConflictException("Company code already exists: " + request.code());
        }
        company.setName(request.name());
        company.setCode(request.code());
        return company;
    }

    public void delete(UUID id) {
        Company company = get(id);
        companyRepository.delete(company);
    }
}
