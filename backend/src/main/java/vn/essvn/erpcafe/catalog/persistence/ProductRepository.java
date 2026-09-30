package vn.essvn.erpcafe.catalog.persistence;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import vn.essvn.erpcafe.catalog.domain.Product;

public interface ProductRepository extends JpaRepository<Product, UUID> {

    Page<Product> findByCompanyId(UUID companyId, Pageable pageable);

    /** Tenant-scoped lookup: empty (→ 404) for another company's id. */
    Optional<Product> findByIdAndCompanyId(UUID id, UUID companyId);

    boolean existsByIdAndCompanyId(UUID id, UUID companyId);

    boolean existsByCompanyIdAndSku(UUID companyId, String sku);
}
