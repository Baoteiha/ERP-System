package vn.essvn.erpcafe.catalog.persistence;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import vn.essvn.erpcafe.catalog.domain.Product;

/** Products are tenant-owned: every JPQL query here sees only the current company's (see {@code TenantOwnedEntity}). */
public interface ProductRepository extends JpaRepository<Product, UUID> {

    List<Product> findByActiveTrueOrderByName();

    boolean existsByCategoryId(UUID categoryId);

    /** Whether any live product has the modifier group attached. */
    @Query("select count(p) > 0 from Product p join p.modifierGroups g where g.id = :groupId")
    boolean existsByModifierGroupId(@Param("groupId") UUID groupId);

    boolean existsBySku(String sku);
}
