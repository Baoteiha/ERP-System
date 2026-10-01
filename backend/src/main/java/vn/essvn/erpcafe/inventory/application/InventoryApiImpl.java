package vn.essvn.erpcafe.inventory.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.essvn.erpcafe.common.domain.Unit;
import vn.essvn.erpcafe.common.exception.BusinessRuleException;
import vn.essvn.erpcafe.inventory.api.CogsResult;
import vn.essvn.erpcafe.inventory.api.InventoryApi;
import vn.essvn.erpcafe.inventory.api.StockLine;
import vn.essvn.erpcafe.inventory.domain.Ingredient;
import vn.essvn.erpcafe.inventory.domain.MovementType;
import vn.essvn.erpcafe.inventory.persistence.IngredientRepository;
import vn.essvn.erpcafe.inventory.persistence.StockMovementRepository;

/** Implements the published {@link InventoryApi} over the stock ledger. */
@Service
public class InventoryApiImpl implements InventoryApi {

    private final IngredientRepository ingredientRepository;
    private final StockLedgerService ledger;
    private final StockMovementRepository movementRepository;

    public InventoryApiImpl(IngredientRepository ingredientRepository, StockLedgerService ledger,
            StockMovementRepository movementRepository) {
        this.ingredientRepository = ingredientRepository;
        this.ledger = ledger;
        this.movementRepository = movementRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public void validateConsumption(UUID companyId, List<StockLine> lines) {
        Map<UUID, Ingredient> ingredients = new HashMap<>();
        ingredientRepository.findAllByIdInAndCompanyId(
                lines.stream().map(StockLine::ingredientId).distinct().toList(), companyId)
                .forEach(i -> ingredients.put(i.getId(), i));
        List<String> problems = new ArrayList<>();
        for (StockLine line : lines) {
            Ingredient ingredient = ingredients.get(line.ingredientId());
            if (ingredient == null) {
                problems.add("Ingredient not found: " + line.ingredientId());
                continue;
            }
            try {
                Unit unit = Unit.parse(line.unit());
                Unit base = Unit.parse(ingredient.getBaseUnit());
                if (!unit.isCompatibleWith(base)) {
                    problems.add("Unit %s does not fit ingredient %s, which is measured in %s"
                            .formatted(unit.symbol(), ingredient.getName(), base.symbol()));
                }
            } catch (BusinessRuleException e) {
                problems.add(e.getMessage());
            }
        }
        if (!problems.isEmpty()) {
            throw new BusinessRuleException(String.join("; ", problems));
        }
    }

    @Override
    @Transactional
    public CogsResult deductForOrder(UUID branchId, List<StockLine> lines, String refType, UUID refId) {
        return ledger.deduct(branchId, lines, refType, refId);
    }

    @Override
    @Transactional
    public CogsResult reverseDeduction(UUID branchId, String refType, UUID refId, String reversalRefType) {
        return ledger.reverseDeduction(branchId, refType, refId, reversalRefType);
    }

    @Override
    @Transactional(readOnly = true)
    public BigDecimal depletionCost(UUID branchId, Instant from, Instant to) {
        BigDecimal depleted = movementRepository.costOfType(branchId, MovementType.SALE_DEPLETION, from, to);
        BigDecimal reversed = movementRepository.costOfType(branchId, MovementType.SALE_REVERSAL, from, to);
        return depleted.subtract(reversed);
    }
}
