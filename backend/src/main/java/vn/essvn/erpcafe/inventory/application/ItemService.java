package vn.essvn.erpcafe.inventory.application;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.essvn.erpcafe.common.domain.Unit;
import vn.essvn.erpcafe.common.exception.BusinessRuleException;
import vn.essvn.erpcafe.common.exception.ConflictException;
import vn.essvn.erpcafe.common.exception.ResourceNotFoundException;
import vn.essvn.erpcafe.identity.security.CurrentUser;
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
    private final CurrentUser currentUser;

    public ItemService(ItemRepository itemRepository, IngredientRepository ingredientRepository,
            SupplierRepository supplierRepository, CurrentUser currentUser) {
        this.itemRepository = itemRepository;
        this.ingredientRepository = ingredientRepository;
        this.supplierRepository = supplierRepository;
        this.currentUser = currentUser;
    }

    @Transactional(readOnly = true)
    public List<Item> list(UUID supplierId, UUID ingredientId) {
        UUID companyId = currentUser.require().companyId();
        if (supplierId != null) {
            return itemRepository.findByCompanyIdAndSupplierId(companyId, supplierId);
        }
        if (ingredientId != null) {
            return itemRepository.findByCompanyIdAndIngredientId(companyId, ingredientId);
        }
        return itemRepository.findByCompanyId(companyId);
    }

    @Transactional(readOnly = true)
    public Item get(UUID id) {
        // Scope to the caller's company: a bare findById would let one company read,
        // edit, or delete another's item by id (IDOR). 404 hides existence.
        return itemRepository.findByIdAndCompanyId(id, currentUser.require().companyId())
                .orElseThrow(() -> ResourceNotFoundException.of("Item", id));
    }

    public Item create(String sku, String name, UUID supplierId, UUID ingredientId, String unit) {
        UUID companyId = currentUser.require().companyId();
        requireSupplier(companyId, supplierId);
        Ingredient ingredient = requireIngredient(companyId, ingredientId);

        String canonicalUnit = compatibleUnit(unit, ingredient).symbol();
        requireUniqueSku(companyId, supplierId, sku);
        requireUniqueMapping(companyId, supplierId, ingredientId, canonicalUnit);

        Item item = new Item(companyId, supplierId, ingredientId, name, canonicalUnit);
        item.setSku(sku);
        return itemRepository.save(item);
    }

    /**
     * Updates the editable face of an item. Supplier and ingredient are deliberately not
     * updatable: an item <em>is</em> a supplier's product for one ingredient, so changing
     * either would silently rewrite what every past purchase against it meant. Point
     * elsewhere by creating a second item and deactivating this one.
     */
    public Item update(UUID id, String sku, String name, String unit, boolean active, Long expectedVersion) {
        Item item = get(id);
        requireCurrentVersion(item.getVersion(), expectedVersion);
        Ingredient ingredient = requireIngredient(item.getCompanyId(), item.getIngredientId());

        // Changing the purchase unit is allowed — it only affects how future receipts are
        // converted. Movements already in the ledger were recorded in the ingredient's base
        // unit, so history keeps its meaning.
        String canonicalUnit = compatibleUnit(unit, ingredient).symbol();
        if (!canonicalUnit.equals(item.getUnit())) {
            requireUniqueMapping(item.getCompanyId(), item.getSupplierId(), item.getIngredientId(), canonicalUnit);
        }
        if (sku != null && !sku.equals(item.getSku())) {
            requireUniqueSku(item.getCompanyId(), item.getSupplierId(), sku);
        }

        item.setSku(sku);
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

    private void requireSupplier(UUID companyId, UUID supplierId) {
        if (supplierRepository.findByIdAndCompanyId(supplierId, companyId).isEmpty()) {
            throw ResourceNotFoundException.of("Supplier", supplierId);
        }
    }

    private Ingredient requireIngredient(UUID companyId, UUID ingredientId) {
        return ingredientRepository.findByIdAndCompanyId(ingredientId, companyId)
                .orElseThrow(() -> ResourceNotFoundException.of("Ingredient", ingredientId));
    }

    // Only ever called for a SKU the item does not already hold, so any existing row
    // carrying it is necessarily a different item.
    private void requireUniqueSku(UUID companyId, UUID supplierId, String sku) {
        if (sku == null || sku.isBlank()) {
            return; // suppliers who don't issue codes shouldn't be forced to invent them
        }
        if (itemRepository.existsByCompanyIdAndSupplierIdAndSku(companyId, supplierId, sku)) {
            throw new ConflictException("Supplier already has an item with SKU: " + sku);
        }
    }

    private void requireUniqueMapping(UUID companyId, UUID supplierId, UUID ingredientId, String unit) {
        if (itemRepository.existsByCompanyIdAndSupplierIdAndIngredientIdAndUnit(
                companyId, supplierId, ingredientId, unit)) {
            throw new ConflictException(
                    "This supplier already sells that ingredient by the " + unit);
        }
    }

    // Stale-form guard: the client echoes the version it loaded. If the row has changed
    // since (version moved on), reject with 409 instead of silently overwriting the newer
    // edit. Enforced only when a version is supplied (older clients stay backward-compatible).
    private static void requireCurrentVersion(Long current, Long expected) {
        if (expected != null && !expected.equals(current)) {
            throw new ConflictException("Item was modified by someone else — reload and try again");
        }
    }
}
