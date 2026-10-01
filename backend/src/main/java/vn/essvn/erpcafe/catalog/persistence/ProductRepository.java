package vn.essvn.erpcafe.catalog.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import vn.essvn.erpcafe.catalog.domain.Product;

public interface ProductRepository extends JpaRepository<Product, UUID> {

    Page<Product> findByCompanyId(UUID companyId, Pageable pageable);

    List<Product> findByCompanyIdAndActiveTrueOrderByName(UUID companyId);

    /** Tenant-scoped lookup: empty (→ 404) for another company's id. */
    Optional<Product> findByIdAndCompanyId(UUID id, UUID companyId);

    boolean existsByIdAndCompanyId(UUID id, UUID companyId);

    boolean existsByCompanyIdAndCategoryId(UUID companyId, UUID categoryId);

    /** Whether any live product has the modifier group attached. */
    @Query("select count(p) > 0 from Product p join p.modifierGroups g where g.id = :groupId")
    boolean existsByModifierGroupId(@Param("groupId") UUID groupId);

    boolean existsByCompanyIdAndSku(UUID companyId, String sku);
}
