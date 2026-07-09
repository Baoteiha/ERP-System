package vn.essvn.erpcafe.catalog.persistence;

import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import vn.essvn.erpcafe.catalog.domain.Product;

public interface ProductRepository extends JpaRepository<Product, UUID> {

    Page<Product> findByCompanyId(UUID companyId, Pageable pageable);

    boolean existsByCompanyIdAndSku(UUID companyId, String sku);
}
