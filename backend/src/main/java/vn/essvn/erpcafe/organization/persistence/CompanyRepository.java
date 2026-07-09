package vn.essvn.erpcafe.organization.persistence;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import vn.essvn.erpcafe.organization.domain.Company;

public interface CompanyRepository extends JpaRepository<Company, UUID> {

    boolean existsByCode(String code);
}
