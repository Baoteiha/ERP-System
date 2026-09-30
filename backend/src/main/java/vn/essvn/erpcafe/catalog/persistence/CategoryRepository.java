package vn.essvn.erpcafe.catalog.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import vn.essvn.erpcafe.catalog.domain.Category;

public interface CategoryRepository extends JpaRepository<Category, UUID> {

    List<Category> findByCompanyIdOrderByDisplayOrderAsc(UUID companyId);

    /** Tenant-scoped lookup: empty (→ 404) for another company's id. */
    Optional<Category> findByIdAndCompanyId(UUID id, UUID companyId);

    boolean existsByCompanyIdAndName(UUID companyId, String name);
}
