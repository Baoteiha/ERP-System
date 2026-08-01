package vn.essvn.erpcafe.inventory.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.essvn.erpcafe.common.domain.Money;
import vn.essvn.erpcafe.inventory.api.CogsResult;
import vn.essvn.erpcafe.inventory.api.StockLine;
import vn.essvn.erpcafe.inventory.domain.MovementType;
import vn.essvn.erpcafe.inventory.domain.StockItem;
import vn.essvn.erpcafe.inventory.domain.StockMovement;
import vn.essvn.erpcafe.inventory.persistence.StockItemRepository;
import vn.essvn.erpcafe.inventory.persistence.StockMovementRepository;

/**
 * The core stock engine. Every change to stock goes through here: it appends an
 * immutable {@link StockMovement} (the source of truth) and updates the cached
 * {@link StockItem} projection, including moving-average cost on receipts.
 */
@Service
@Transactional
public class StockLedgerService {

    private static final Logger log = LoggerFactory.getLogger(StockLedgerService.class);
    private static final int CALC_SCALE = 10;

    private final StockItemRepository stockItemRepository;
    private final StockMovementRepository movementRepository;

    public StockLedgerService(StockItemRepository stockItemRepository,
            StockMovementRepository movementRepository) {
        this.stockItemRepository = stockItemRepository;
        this.movementRepository = movementRepository;
    }

    /** Receives stock (e.g. from a purchase order): raises quantity and blends the moving-average cost. */
    public StockItem receive(UUID branchId, UUID ingredientId, BigDecimal quantity, Money unitCost,
            String refType, UUID refId) {
        StockItem item = getOrCreate(branchId, ingredientId);
        BigDecimal existingQty = item.getQuantityOnHand();
        BigDecimal newQty = existingQty.add(quantity);

        // Blend cost BEFORE overwriting quantity — the average needs the pre-receipt quantity.
        item.setAvgUnitCost(newMovingAverage(existingQty, item.getAvgUnitCost(), quantity, unitCost, newQty));
        item.setQuantityOnHand(newQty);

        record(ingredientId, branchId, MovementType.PURCHASE_RECEIPT, quantity, unitCost, refType, refId, null);
        return item;
    }

    /** Manual stock count correction (signed). Cost is unchanged. */
    public StockItem adjust(UUID branchId, UUID ingredientId, BigDecimal quantityDelta, String note) {
        StockItem item = getOrCreate(branchId, ingredientId);
        item.setQuantityOnHand(item.getQuantityOnHand().add(quantityDelta));
        record(ingredientId, branchId, MovementType.ADJUSTMENT, quantityDelta, item.getAvgUnitCost(),
                "ADJUSTMENT", null, note);
        return item;
    }

    /** Records waste/spoilage: reduces quantity by the given positive amount. */
    public StockItem waste(UUID branchId, UUID ingredientId, BigDecimal quantity, String note) {
        StockItem item = getOrCreate(branchId, ingredientId);
        item.setQuantityOnHand(item.getQuantityOnHand().subtract(quantity));
        record(ingredientId, branchId, MovementType.WASTE, quantity.negate(), item.getAvgUnitCost(),
                "WASTE", null, note);
        return item;
    }

    /**
     * Deducts ingredient quantities for an order and returns COGS. Idempotent per
     * {@code (refType, refId)}; allows stock to go negative (warn-but-allow).
     */
    public CogsResult deduct(UUID branchId, List<StockLine> lines, String refType, UUID refId) {
        // Idempotency guard: if this order already deducted (e.g. a retried completion),
        // don't deduct again — return the COGS already recorded for this reference.
        if (refId != null && movementRepository.existsByRefTypeAndRefId(refType, refId)) {
            return new CogsResult(cogsOfExisting(refType, refId));
        }
        Money total = Money.zero();
        for (StockLine line : normalize(lines)) {
            StockItem item = getOrCreate(branchId, line.ingredientId());
            // COGS values the depletion at the CURRENT moving-average cost.
            Money lineCost = item.getAvgUnitCost().multiply(line.quantity());
            total = total.add(lineCost);

            BigDecimal newQty = item.getQuantityOnHand().subtract(line.quantity());
            if (newQty.signum() < 0) {
                // Warn-but-allow: a cafe shouldn't block a sale over a mis-counted gram;
                // the negative balance is a signal to reconcile, not a hard stop.
                log.warn("Stock for ingredient {} at branch {} went negative ({}) via {}:{}",
                        line.ingredientId(), branchId, newQty, refType, refId);
            }
            item.setQuantityOnHand(newQty);
            record(line.ingredientId(), branchId, MovementType.SALE_DEPLETION,
                    line.quantity().negate(), item.getAvgUnitCost(), refType, refId, null);
        }
        return new CogsResult(total);
    }

    /**
     * Returns stock (compensating a void/refund): raises quantity and records a
     * SALE_REVERSAL. Idempotent per {@code (refType, refId)}. Cost is unchanged —
     * a return isn't a purchase, so it doesn't re-blend the moving average.
     */
    public void returnStock(UUID branchId, List<StockLine> lines, String refType, UUID refId) {
        if (refId != null && movementRepository.existsByRefTypeAndRefId(refType, refId)) {
            return; // already returned → no-op
        }
        for (StockLine line : normalize(lines)) {
            StockItem item = getOrCreate(branchId, line.ingredientId());
            item.setQuantityOnHand(item.getQuantityOnHand().add(line.quantity()));
            record(line.ingredientId(), branchId, MovementType.SALE_REVERSAL,
                    line.quantity(), item.getAvgUnitCost(), refType, refId, null);
        }
    }

    private Money cogsOfExisting(String refType, UUID refId) {
        Money total = Money.zero();
        for (StockMovement m : movementRepository.findByRefTypeAndRefId(refType, refId)) {
            total = total.add(m.getUnitCost().multiply(m.getQuantity().abs()));
        }
        return total;
    }

    /**
     * Orders lines by {@code ingredientId} and merges duplicates into one line.
     *
     * <p>The sort is what prevents deadlock. Row locks are held until commit, so two
     * transactions that lock the same ingredients in different orders can each end up
     * holding what the other needs — a wait-for cycle Postgres can only break by killing
     * one. Acquiring in a consistent global order makes that impossible: a transaction
     * holding ingredient X only ever waits on some Y &gt; X, so no cycle can close. Every
     * multi-row stock path must use this same order (see also
     * {@code PurchaseOrderService.receive}).
     *
     * <p>Merging duplicates is a bonus: an order with two lattes hits milk twice, and
     * collapsing them means one lock and one movement row instead of two.
     */
    private static List<StockLine> normalize(List<StockLine> lines) {
        Map<UUID, StockLine> merged = new TreeMap<>();
        for (StockLine line : lines) {
            merged.merge(line.ingredientId(), line,
                    (a, b) -> new StockLine(a.ingredientId(), a.quantity().add(b.quantity()), a.unit()));
        }
        return List.copyOf(merged.values());
    }

    // Loads the stock row under a pessimistic write lock (SELECT ... FOR UPDATE) so
    // concurrent receipts/depletions of the same item serialize instead of losing updates.
    private StockItem getOrCreate(UUID branchId, UUID ingredientId) {
        return stockItemRepository.findForUpdate(ingredientId, branchId)
                .orElseGet(() -> stockItemRepository.save(new StockItem(ingredientId, branchId)));
    }

    // Weighted moving average: new cost = (value already on hand + value received) / total quantity.
    // e.g. 1000g @0.20 + 1000g @0.24 -> (200 + 240) / 2000 = 0.22 per unit.
    private Money newMovingAverage(BigDecimal existingQty, Money existingAvg, BigDecimal addedQty,
            Money addedCost, BigDecimal newQty) {
        if (newQty.signum() <= 0) {
            return addedCost; // degenerate (e.g. was oversold to <= 0): fall back to the incoming cost
        }
        BigDecimal existingValue = existingQty.multiply(existingAvg.getAmount());
        BigDecimal addedValue = addedQty.multiply(addedCost.getAmount());
        // Divide at a wider scale first; Money.of() then rounds to its 4-dp storage scale.
        BigDecimal avg = existingValue.add(addedValue).divide(newQty, CALC_SCALE, RoundingMode.HALF_EVEN);
        return Money.of(avg);
    }

    private void record(UUID ingredientId, UUID branchId, MovementType type, BigDecimal quantity,
            Money unitCost, String refType, UUID refId, String note) {
        movementRepository.save(new StockMovement(ingredientId, branchId, type, quantity, unitCost, refType, refId, note));
    }
}
