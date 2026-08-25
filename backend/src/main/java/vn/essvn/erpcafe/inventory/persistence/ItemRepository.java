package vn.essvn.erpcafe.inventory.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import vn.essvn.erpcafe.inventory.domain.Item;

public interface ItemRepository extends JpaRepository<Item, UUID> {

    List<Item> findByCompanyId(UUID companyId);

    /** A supplier's catalogue — what the PO form offers once a supplier is chosen. */
    List<Item> findByCompanyIdAndSupplierId(UUID companyId, UUID supplierId);

    /** Every way this ingredient can be bought, across suppliers. */
    List<Item> findByCompanyIdAndIngredientId(UUID companyId, UUID ingredientId);

    /** Whether any live item (active or not) still references the ingredient. */
    boolean existsByCompanyIdAndIngredientId(UUID companyId, UUID ingredientId);

    /** Tenant-scoped lookup: returns empty (→ 404) for another company's id. */
    Optional<Item> findByIdAndCompanyId(UUID id, UUID companyId);

    boolean existsByCompanyIdAndSupplierIdAndSku(UUID companyId, UUID supplierId, String sku);

    boolean existsByCompanyIdAndSupplierIdAndIngredientIdAndUnit(
            UUID companyId, UUID supplierId, UUID ingredientId, String unit);
}
