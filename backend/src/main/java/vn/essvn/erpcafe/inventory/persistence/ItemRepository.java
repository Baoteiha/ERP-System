package vn.essvn.erpcafe.inventory.persistence;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import vn.essvn.erpcafe.inventory.domain.Item;

/** Items are tenant-owned: every query here sees only the current company's (see {@code TenantOwnedEntity}). */
@Transactional(readOnly = true)
public interface ItemRepository extends JpaRepository<Item, UUID> {

    /** A supplier's catalogue — what the PO form offers once a supplier is chosen. */
    List<Item> findBySupplierId(UUID supplierId);

    /** Every way this ingredient can be bought, across suppliers. */
    List<Item> findByIngredientId(UUID ingredientId);

    /** Whether any live item (active or not) still references the ingredient. */
    boolean existsByIngredientId(UUID ingredientId);

    boolean existsBySupplierIdAndSku(UUID supplierId, String sku);

    /** The supplier's live items for an ingredient in one unit; the service compares their packs. */
    List<Item> findBySupplierIdAndIngredientIdAndUnit(UUID supplierId, UUID ingredientId, String unit);
}
