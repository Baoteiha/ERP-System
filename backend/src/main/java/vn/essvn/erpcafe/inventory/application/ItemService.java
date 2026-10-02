package vn.essvn.erpcafe.inventory.application;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.essvn.erpcafe.common.domain.Unit;
import vn.essvn.erpcafe.common.exception.BusinessRuleException;
import vn.essvn.erpcafe.common.exception.ConflictException;
import vn.essvn.erpcafe.common.exception.ResourceNotFoundException;
import vn.essvn.erpcafe.inventory.domain.Ingredient;
import vn.essvn.erpcafe.inventory.domain.Item;
import vn.essvn.erpcafe.inventory.persistence.IngredientRepository;
import vn.essvn.erpcafe.inventory.persistence.ItemRepository;
import vn.essvn.erpcafe.inventory.persistence.SupplierRepository;

/**
 * Item master-data management within the acting user's company: what each supplier
 * sells, and which ingredient it becomes.
 *
 * <p>The rule this service exists to keep is the unit one — an item's purchase unit
 * must measure the same thing as the ingredient's base unit. Enforced once here, at
 * creation, so receiving can convert with a pure function and never has to decide
 * whether a number makes sense.
 */
@Service
@Transactional
public class ItemService {

    private final ItemRepository itemRepository;
    private final IngredientRepository ingredientRepository;
    private final SupplierRepository supplierRepository;

    public ItemService(ItemRepository itemRepository, IngredientRepository ingredientRepository,
            SupplierRepository supplierRepository) {
        this.itemRepository = itemRepository;
        this.ingredientRepository = ingredientRepository;
        this.supplierRepository = supplierRepository;
    }

    @Transactional(readOnly = true)
    public List<Item> list(UUID supplierId, UUID ingredientId) {
        if (supplierId != null) {
            return itemRepository.findBySupplierId(supplierId);
        }
        if (ingredientId != null) {
            return itemRepository.findByIngredientId(ingredientId);
        }
        return itemRepository.findAll();
    }

    /** Another company's item is not found (404): the tenant seam scopes the lookup. */
    @Transactional(readOnly = true)
    public Item get(UUID id) {
        return itemRepository.findById(id)
                .orElseThrow(() -> ResourceNotFoundException.of("Item", id));
    }

    public Item create(String sku, String name, UUID supplierId, UUID ingredientId, String unit) {
        requireSupplier(supplierId);
        Ingredient ingredient = requireIngredient(ingredientId);

        String canonicalUnit = compatibleUnit(unit, ingredient).symbol();
        String normalizedSku = normalizeSku(sku);
        requireUniqueSku(supplierId, normalizedSku);
        requireUniqueMapping(supplierId, ingredientId, canonicalUnit);

        Item item = new Item(supplierId, ingredientId, name, canonicalUnit);
        item.setSku(normalizedSku);
        return itemRepository.save(item);
    }

    /**
     * Updates the editable face of an item. Supplier and ingredient are deliberately not
     * updatable: an item <em>is</em> a supplier's product for one ingredient, so changing
     * either would silently rewrite what every past purchase against it meant. A request
     * that tries is rejected with 409 — never accepted-and-ignored. Point elsewhere by
     * creating a second item and deactivating this one.
     */
    public Item update(UUID id, String sku, String name, UUID supplierId, UUID ingredientId,
            String unit, boolean active, Long expectedVersion) {
        Item item = get(id);
        ConflictException.requireCurrentVersion(item.getVersion(), expectedVersion);
        if (supplierId != null && !supplierId.equals(item.getSupplierId())) {
            throw new ConflictException("An item's supplier cannot change; create a new item instead");
        }
        if (ingredientId != null && !ingredientId.equals(item.getIngredientId())) {
            throw new ConflictException("An item's ingredient cannot change; create a new item instead");
        }
        Ingredient ingredient = requireIngredient(item.getIngredientId());

        // Changing the purchase unit is allowed — it only affects how future receipts are
        // converted. Movements already in the ledger were recorded in the ingredient's base
        // unit, so history keeps its meaning.
        String canonicalUnit = compatibleUnit(unit, ingredient).symbol();
        if (!canonicalUnit.equals(item.getUnit())) {
            requireUniqueMapping(item.getSupplierId(), item.getIngredientId(), canonicalUnit);
        }
        String normalizedSku = normalizeSku(sku);
        if (normalizedSku != null && !normalizedSku.equals(item.getSku())) {
            requireUniqueSku(item.getSupplierId(), normalizedSku);
        }

        item.setSku(normalizedSku);
        item.setName(name);
        item.setUnit(canonicalUnit);
        item.setActive(active);
        return item;
    }

    public void delete(UUID id) {
        itemRepository.delete(get(id));
    }

    // An item priced in kilograms against an ingredient counted in millilitres is not a
    // rounding problem, it is a mapping error — and the only moment anyone can still
    // sensibly fix it is now, before a delivery is standing in the doorway.
    private static Unit compatibleUnit(String unit, Ingredient ingredient) {
        Unit purchaseUnit = Unit.parse(unit);
        Unit baseUnit = Unit.parse(ingredient.getBaseUnit());
        if (!purchaseUnit.isCompatibleWith(baseUnit)) {
            throw new BusinessRuleException(
                    "Cannot buy %s in %s: %s is counted in %s".formatted(
                            ingredient.getName(), purchaseUnit.symbol(), ingredient.getName(), baseUnit.symbol()));
        }
        return purchaseUnit;
    }

    private void requireSupplier(UUID supplierId) {
        if (!supplierRepository.existsById(supplierId)) {
            throw ResourceNotFoundException.of("Supplier", supplierId);
        }
    }

    private Ingredient requireIngredient(UUID ingredientId) {
        return ingredientRepository.findById(ingredientId)
                .orElseThrow(() -> ResourceNotFoundException.of("Ingredient", ingredientId));
    }

    // The persistence contract is null-or-real: a blank SKU stored as '' would collide in
    // the partial unique index (WHERE sku IS NOT NULL), turning the second SKU-less item
    // for a supplier into a 500. Suppliers who issue no codes get null, uniformly.
    private static String normalizeSku(String sku) {
        return sku == null || sku.isBlank() ? null : sku.trim();
    }

    // Only ever called for a SKU the item does not already hold, so any existing row
    // carrying it is necessarily a different item.
    private void requireUniqueSku(UUID supplierId, String sku) {
        if (sku == null || sku.isBlank()) {
            return; // suppliers who don't issue codes shouldn't be forced to invent them
        }
        if (itemRepository.existsBySupplierIdAndSku(supplierId, sku)) {
            throw new ConflictException("Supplier already has an item with SKU: " + sku);
        }
    }

    private void requireUniqueMapping(UUID supplierId, UUID ingredientId, String unit) {
        if (itemRepository.existsBySupplierIdAndIngredientIdAndUnit(supplierId, ingredientId, unit)) {
            throw new ConflictException(
                    "This supplier already sells that ingredient by the " + unit);
        }
    }
}
