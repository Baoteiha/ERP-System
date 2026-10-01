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
import vn.essvn.erpcafe.inventory.api.IngredientReferenceCheck;
import vn.essvn.erpcafe.inventory.domain.Ingredient;
import vn.essvn.erpcafe.inventory.persistence.IngredientRepository;
import vn.essvn.erpcafe.inventory.domain.PurchaseOrderStatus;
import vn.essvn.erpcafe.inventory.persistence.ItemRepository;
import vn.essvn.erpcafe.inventory.persistence.PurchaseOrderRepository;
import vn.essvn.erpcafe.inventory.persistence.StockItemRepository;
import vn.essvn.erpcafe.inventory.persistence.StockMovementRepository;

/** Ingredient master-data management within the acting user's company. */
@Service
@Transactional
public class IngredientService {

    private final IngredientRepository ingredientRepository;
    private final ItemRepository itemRepository;
    private final StockItemRepository stockItemRepository;
    private final StockMovementRepository movementRepository;
    private final PurchaseOrderRepository purchaseOrderRepository;
    private final CurrentUser currentUser;
    private final List<IngredientReferenceCheck> referenceChecks;

    public IngredientService(IngredientRepository ingredientRepository, ItemRepository itemRepository,
            StockItemRepository stockItemRepository, StockMovementRepository movementRepository,
            PurchaseOrderRepository purchaseOrderRepository, CurrentUser currentUser,
            List<IngredientReferenceCheck> referenceChecks) {
        this.referenceChecks = referenceChecks;
        this.stockItemRepository = stockItemRepository;
        this.movementRepository = movementRepository;
        this.purchaseOrderRepository = purchaseOrderRepository;
        this.ingredientRepository = ingredientRepository;
        this.itemRepository = itemRepository;
        this.currentUser = currentUser;
    }

    @Transactional(readOnly = true)
    public List<Ingredient> list() {
        return ingredientRepository.findByCompanyId(currentUser.require().companyId());
    }

    @Transactional(readOnly = true)
    public Ingredient get(UUID id) {
        // Scope to the caller's company: a bare findById would let one company read,
        // edit, or delete another's ingredient by id (IDOR). 404 hides existence.
        return ingredientRepository.findByIdAndCompanyId(id, currentUser.require().companyId())
                .orElseThrow(() -> ResourceNotFoundException.of("Ingredient", id));
    }

    public Ingredient create(String name, String baseUnit, String category) {
        UUID companyId = currentUser.require().companyId();
        if (ingredientRepository.existsByCompanyIdAndName(companyId, name)) {
            throw new ConflictException("Ingredient already exists: " + name);
        }
        return ingredientRepository.save(new Ingredient(companyId, name, canonicalUnit(baseUnit), category));
    }

    public Ingredient update(UUID id, String name, String baseUnit, String category, boolean active,
            Long expectedVersion) {
        Ingredient ingredient = get(id);
        requireCurrentVersion(ingredient.getVersion(), expectedVersion);
        if (!ingredient.getName().equals(name)
                && ingredientRepository.existsByCompanyIdAndName(ingredient.getCompanyId(), name)) {
            throw new ConflictException("Ingredient already exists: " + name);
        }
        ingredient.setName(name);
        String newBaseUnit = canonicalUnit(baseUnit);
        guardBaseUnitChange(ingredient, newBaseUnit);
        ingredient.setBaseUnit(newBaseUnit);
        ingredient.setCategory(category);
        ingredient.setActive(active);
        return ingredient;
    }

    // Base unit is the denominator of everything downstream — stock on hand, the
    // moving-average cost, every recipe line measured against it. Free text let "kg" and
    // "g" describe the same shelf, so it is resolved to a known Unit here and stored in
    // one canonical spelling; unrecognised text is rejected rather than guessed.
    private static String canonicalUnit(String baseUnit) {
        return Unit.parse(baseUnit).symbol();
    }

    // Stock and movement quantities are denominated in the base unit, so relabelling it would
    // silently rescale history. A different dimension can never be right; within one dimension the
    // change is only safe while nothing has ever been counted in the old unit.
    private void guardBaseUnitChange(Ingredient ingredient, String newSymbol) {
        Unit current = Unit.parse(ingredient.getBaseUnit());
        Unit requested = Unit.parse(newSymbol);
        if (current == requested) {
            return;
        }
        if (current.dimension() != requested.dimension()) {
            throw new BusinessRuleException("Base unit cannot change from %s to %s: they measure different things"
                    .formatted(current.symbol(), requested.symbol()));
        }
        UUID id = ingredient.getId();
        if (stockItemRepository.existsNonZeroStock(id) || movementRepository.existsByIngredientId(id)) {
            throw new ConflictException(
                    "Base unit cannot change once the ingredient has stock or movements");
        }
    }

    // Stale-form guard: the client echoes the version it loaded. If the row has changed
    // since (version moved on), reject with 409 instead of silently overwriting the newer
    // edit. Enforced only when a version is supplied (older clients stay backward-compatible).
    private static void requireCurrentVersion(Long current, Long expected) {
        if (expected != null && !expected.equals(current)) {
            throw new ConflictException(ConflictException.STALE_EDIT_MESSAGE);
        }
    }

    public void delete(UUID id) {
        Ingredient ingredient = get(id);
        // Items resolve their ingredient on every edit, so soft-deleting it out from under
        // them would strand each one behind a misleading 404. Refuse while any live item
        // (active or not) still references this ingredient.
        if (itemRepository.existsByCompanyIdAndIngredientId(ingredient.getCompanyId(), id)) {
            throw new ConflictException(
                    "Ingredient is still referenced by supplier items; delete or repoint those first");
        }
        if (stockItemRepository.existsNonZeroStock(id)) {
            throw new ConflictException("Ingredient still has stock at a branch; bring it to zero first");
        }
        if (purchaseOrderRepository.existsOpenLine(id, PurchaseOrderStatus.CANCELLED)) {
            throw new ConflictException("Ingredient is on an open purchase order; receive or cancel it first");
        }
        // Other modules (catalog recipes and modifier deltas) report their own references through
        // the port; a deleted ingredient would leave them pointing at nothing and fail every sale.
        if (referenceChecks.stream().anyMatch(c -> c.isReferenced(ingredient.getCompanyId(), id))) {
            throw new ConflictException("Ingredient is still used by a recipe or modifier; remove it there first");
        }
        ingredientRepository.delete(ingredient);
    }
}
