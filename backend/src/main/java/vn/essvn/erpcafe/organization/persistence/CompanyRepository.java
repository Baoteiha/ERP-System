package vn.essvn.erpcafe.organization.persistence;

import java.util.Collection;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import vn.essvn.erpcafe.organization.domain.Company;

@Transactional(readOnly = true)
public interface CompanyRepository extends JpaRepository<Company, UUID> {

    boolean existsByCode(String code);

    /** Paged self-lookup: the caller's own company is the only one they may list. */
    Page<Company> findByIdIn(Collection<UUID> ids, Pageable pageable);
}
