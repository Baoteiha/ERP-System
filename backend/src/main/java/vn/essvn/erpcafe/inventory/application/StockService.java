package vn.essvn.erpcafe.inventory.application;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.essvn.erpcafe.common.context.BranchContext;
import vn.essvn.erpcafe.common.exception.BusinessRuleException;
import vn.essvn.erpcafe.common.exception.ResourceNotFoundException;
import vn.essvn.erpcafe.identity.security.CurrentUser;
import vn.essvn.erpcafe.inventory.domain.Ingredient;
import vn.essvn.erpcafe.inventory.domain.StockItem;
import vn.essvn.erpcafe.inventory.domain.StockMovement;
import vn.essvn.erpcafe.inventory.persistence.IngredientRepository;
import vn.essvn.erpcafe.inventory.persistence.StockItemRepository;
import vn.essvn.erpcafe.inventory.persistence.StockMovementRepository;

/**
 * Branch-scoped stock operations for the active branch (from {@code X-Branch-Id}):
 * viewing stock and the movement ledger, manual adjustments, waste, reorder
 * levels, and low-stock alerts.
 */
@Service
@Transactional
public class StockService {

    private final StockItemRepository stockItemRepository;
    private final StockMovementRepository movementRepository;
    private final IngredientRepository ingredientRepository;
    private final StockLedgerService ledger;
    private final CurrentUser currentUser;

    public StockService(StockItemRepository stockItemRepository, StockMovementRepository movementRepository,
            IngredientRepository ingredientRepository, StockLedgerService ledger, CurrentUser currentUser) {
        this.stockItemRepository = stockItemRepository;
        this.movementRepository = movementRepository;
        this.ingredientRepository = ingredientRepository;
        this.ledger = ledger;
        this.currentUser = currentUser;
    }

    @Transactional(readOnly = true)
    public List<StockItem> listStock() {
        return stockItemRepository.findByBranchId(activeBranch());
    }

    @Transactional(readOnly = true)
    public List<StockItem> lowStock() {
        return stockItemRepository.findLowStock(activeBranch());
    }

    @Transactional(readOnly = true)
    public Page<StockMovement> movements(UUID ingredientId, Pageable pageable) {
        UUID branchId = activeBranch();
        return ingredientId == null
                ? movementRepository.findByBranchIdOrderByCreatedAtDesc(branchId, pageable)
                : movementRepository.findByBranchIdAndIngredientIdOrderByCreatedAtDesc(branchId, ingredientId, pageable);
    }

    public StockItem adjust(UUID ingredientId, BigDecimal quantityDelta, String note) {
        requireIngredient(ingredientId);
        return ledger.adjust(activeBranch(), ingredientId, quantityDelta, note);
    }

    public StockItem waste(UUID ingredientId, BigDecimal quantity, String note) {
        if (quantity.signum() <= 0) {
            throw new BusinessRuleException("Waste quantity must be positive");
        }
        requireIngredient(ingredientId);
        return ledger.waste(activeBranch(), ingredientId, quantity, note);
    }

    public StockItem setReorderLevel(UUID ingredientId, BigDecimal reorderLevel) {
        requireIngredient(ingredientId);
        UUID branchId = activeBranch();
        StockItem item = stockItemRepository.findByIngredientIdAndBranchId(ingredientId, branchId)
                .orElseGet(() -> stockItemRepository.save(new StockItem(ingredientId, branchId)));
        item.setReorderLevel(reorderLevel);
        return item;
    }

    private void requireIngredient(UUID ingredientId) {
        Ingredient ingredient = ingredientRepository.findById(ingredientId)
                .orElseThrow(() -> ResourceNotFoundException.of("Ingredient", ingredientId));
        if (!ingredient.getCompanyId().equals(currentUser.require().companyId())) {
            throw new BusinessRuleException("Ingredient belongs to a different company");
        }
    }

    private UUID activeBranch() {
        return BranchContext.current()
                .orElseThrow(() -> new BusinessRuleException("Active branch required (set X-Branch-Id header)"));
    }
}
