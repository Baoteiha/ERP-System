package vn.essvn.erpcafe.inventory.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;
import vn.essvn.erpcafe.common.domain.Money;
import vn.essvn.erpcafe.common.domain.Unit;
import vn.essvn.erpcafe.common.exception.BusinessRuleException;
import vn.essvn.erpcafe.inventory.api.CogsResult;
import vn.essvn.erpcafe.inventory.api.StockLine;
import vn.essvn.erpcafe.inventory.domain.Ingredient;
import vn.essvn.erpcafe.inventory.domain.MovementType;
import vn.essvn.erpcafe.inventory.domain.StockItem;
import vn.essvn.erpcafe.inventory.domain.StockMovement;
import vn.essvn.erpcafe.inventory.persistence.IngredientRepository;
import vn.essvn.erpcafe.inventory.persistence.StockItemRepository;
import vn.essvn.erpcafe.inventory.persistence.StockMovementRepository;
import vn.essvn.erpcafe.organization.api.OrganizationApi;

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
    // The scale of the NUMERIC(19,4) quantity columns: a converted quantity is rounded to it here, once,
    // rather than by the database on insert.
    private static final int QUANTITY_SCALE = 4;

    private final StockItemRepository stockItemRepository;
    private final StockMovementRepository movementRepository;
    private final IngredientRepository ingredientRepository;
    private final OrganizationApi organizationApi;
    private final EntityManager entityManager;

    public StockLedgerService(StockItemRepository stockItemRepository,
            StockMovementRepository movementRepository, IngredientRepository ingredientRepository,
            OrganizationApi organizationApi, EntityManager entityManager) {
        this.entityManager = entityManager;
        this.organizationApi = organizationApi;
        this.stockItemRepository = stockItemRepository;
        this.movementRepository = movementRepository;
        this.ingredientRepository = ingredientRepository;
    }

    /**
     * Receives stock (e.g. from a purchase order): raises quantity and blends the moving-average cost.
     * {@code quantity} is in {@code unit} (blank = base unit); {@code unitCost} is always per base unit.
     */
    public StockItem receive(UUID branchId, UUID ingredientId, BigDecimal quantity, String unit,
            Money unitCost, String refType, UUID refId) {
        quantity = toBase(branchId, ingredientId, quantity, unit);
        StockItem item = getOrCreate(branchId, ingredientId);
        BigDecimal existingQty = item.getQuantityOnHand();
        BigDecimal newQty = existingQty.add(quantity);

        // Blend cost BEFORE overwriting quantity — the average needs the pre-receipt quantity.
        item.setAvgUnitCost(newMovingAverage(existingQty, item.getAvgUnitCost(), quantity, unitCost, newQty));
        item.setQuantityOnHand(newQty);

        record(ingredientId, branchId, MovementType.PURCHASE_RECEIPT, quantity, unitCost, refType, refId, null);
        return item;
    }

    /** Manual stock count correction (signed, in {@code unit}; blank = base unit). Cost is unchanged. */
    public StockItem adjust(UUID branchId, UUID ingredientId, BigDecimal quantityDelta, String unit, String note) {
        quantityDelta = toBase(branchId, ingredientId, quantityDelta, unit);
        StockItem item = getOrCreate(branchId, ingredientId);
        item.setQuantityOnHand(item.getQuantityOnHand().add(quantityDelta));
        record(ingredientId, branchId, MovementType.ADJUSTMENT, quantityDelta, item.getAvgUnitCost(),
                "ADJUSTMENT", null, note);
        return item;
    }

    /** Records waste/spoilage: reduces quantity by the given positive amount in {@code unit} (blank = base unit). */
    public StockItem waste(UUID branchId, UUID ingredientId, BigDecimal quantity, String unit, String note) {
        quantity = toBase(branchId, ingredientId, quantity, unit);
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
        // The existence check covers rows deducted before ledger_reference existed; the claim is what
        // makes two concurrent calls safe — the loser waits on the winner's commit and gets 0 rows.
        if (refId != null && (movementRepository.existsByRefTypeAndRefId(refType, refId)
                || !claim(refType, refId, branchId))) {
            return new CogsResult(cogsOfExisting(refType, refId));
        }
        Money total = Money.zero();
        for (StockLine line : normalize(branchId, lines)) {
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
     * Undoes what a sale actually took: for each SALE_DEPLETION recorded under
     * {@code (refType, refId)} it returns the same quantity at the same unit cost and
     * writes a SALE_REVERSAL under {@code (reversalRefType, refId)}. The recipe is never
     * consulted — it may have changed since the sale. The returned stock is treated as a
     * receipt at the original cost, so it re-blends the moving average. A repeat call for
     * the same reversal reference is a no-op returning zero cost. The claim is keyed on the
     * original reference, so a void and a refund of one order can never both restore stock.
     */
    public CogsResult reverseDeduction(UUID branchId, String refType, UUID refId, String reversalRefType) {
        if (movementRepository.existsByRefTypeAndRefId(reversalRefType, refId)
                || !claim("REVERSAL:" + refType, refId, branchId)) {
            return new CogsResult(Money.zero());
        }
        List<StockMovement> depletions = movementRepository.findByRefTypeAndRefId(refType, refId).stream()
                .filter(m -> m.getType() == MovementType.SALE_DEPLETION && branchId.equals(m.getBranchId()))
                // Same global lock order as normalize(): ingredient id ascending.
                .sorted(Comparator.comparing(StockMovement::getIngredientId))
                .toList();
        Money total = Money.zero();
        for (StockMovement depletion : depletions) {
            // Opposite sign of the depletion: a modifier can make net consumption negative,
            // which deduct() records as a positive depletion — its reversal takes the stock back out.
            BigDecimal quantity = depletion.getQuantity().negate();
            if (quantity.signum() == 0) {
                continue;
            }
            Money unitCost = depletion.getUnitCost();
            StockItem item = getOrCreate(branchId, depletion.getIngredientId());
            BigDecimal newQty = item.getQuantityOnHand().add(quantity);
            if (quantity.signum() > 0) {
                item.setAvgUnitCost(newMovingAverage(item.getQuantityOnHand(), item.getAvgUnitCost(), quantity,
                        unitCost, newQty));
            }
            item.setQuantityOnHand(newQty);
            record(depletion.getIngredientId(), branchId, MovementType.SALE_REVERSAL, quantity, unitCost,
                    reversalRefType, refId, null);
            total = total.add(unitCost.multiply(quantity));
        }
        return new CogsResult(total);
    }

    // INSERT ... ON CONFLICT DO NOTHING inside the business transaction: a concurrent claimer blocks
    // on the primary key until we commit or roll back, and a rollback releases the claim.
    private boolean claim(String refType, UUID refId, UUID branchId) {
        return entityManager.createNativeQuery(
                "INSERT INTO ledger_reference (ref_type, ref_id, branch_id) VALUES (:refType, :refId, :branchId) "
                        + "ON CONFLICT DO NOTHING")
                .setParameter("refType", refType)
                .setParameter("refId", refId)
                .setParameter("branchId", branchId)
                .executeUpdate() == 1;
    }

    private Money cogsOfExisting(String refType, UUID refId) {
        Money total = Money.zero();
        for (StockMovement m : movementRepository.findByRefTypeAndRefId(refType, refId)) {
            // Same sign as deduct(): cost = -quantity x unit cost, so a positive-quantity depletion
            // (net-negative modifier consumption) is a negative cost.
            total = total.add(m.getUnitCost().multiply(m.getQuantity().negate()));
        }
        return total;
    }

    /**
     * Converts every line to its ingredient's base unit, then orders lines by
     * {@code ingredientId} and merges duplicates into one line.
     *
     * <p>Conversion comes first (ADR-0006): a recipe line in kg and a modifier delta in g
     * are the same ingredient measured differently, and adding them raw is how stock ends
     * up wrong by a factor of a thousand. A line whose unit cannot convert to the base
     * unit (wrong dimension, unknown text) is a caller bug and is rejected, never guessed.
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
    private List<StockLine> normalize(UUID branchId, List<StockLine> lines) {
        Map<UUID, Unit> baseUnits = baseUnitsOf(branchId, lines.stream().map(StockLine::ingredientId).toList());
        Map<UUID, StockLine> merged = new TreeMap<>();
        for (StockLine line : lines) {
            Unit base = requireBase(baseUnits, line.ingredientId());
            StockLine converted = new StockLine(line.ingredientId(),
                    convert(line.quantity(), line.unit(), base), base.symbol());
            merged.merge(converted.ingredientId(), converted,
                    (a, b) -> new StockLine(a.ingredientId(), a.quantity().add(b.quantity()), a.unit()));
        }
        return List.copyOf(merged.values());
    }

    /**
     * The one place a quantity becomes base-unit stock: deduct, receive, adjust, waste and the
     * receiving outstanding check all convert through here. A blank unit means the caller already
     * speaks base units; a unit of another dimension is rejected, never guessed. The ingredient
     * must belong to the company that owns the branch — a foreign one is treated like a missing one.
     */
    public BigDecimal toBase(UUID branchId, UUID ingredientId, BigDecimal quantity, String unit) {
        Unit base = requireBase(baseUnitsOf(branchId, List.of(ingredientId)), ingredientId);
        return convert(quantity, unit, base);
    }

    /** Re-rates a cost given per {@code unit} to per base unit; the price counterpart of {@link #toBase}. */
    public BigDecimal toBaseRate(UUID branchId, UUID ingredientId, BigDecimal ratePerUnit, String unit) {
        Unit base = requireBase(baseUnitsOf(branchId, List.of(ingredientId)), ingredientId);
        if (unit == null || unit.isBlank() || Unit.parse(unit) == base) {
            return ratePerUnit;
        }
        return Unit.convertRate(ratePerUnit, Unit.parse(unit), base);
    }

    private static Unit requireBase(Map<UUID, Unit> baseUnits, UUID ingredientId) {
        Unit base = baseUnits.get(ingredientId);
        if (base == null) {
            // Missing, soft-deleted or foreign ingredient: guessing here would book stock against nothing.
            throw new BusinessRuleException("Ingredient not found: " + ingredientId);
        }
        return base;
    }

    private static BigDecimal convert(BigDecimal quantity, String unit, Unit base) {
        if (unit == null || unit.isBlank()) {
            return quantity;
        }
        return Unit.convert(quantity, Unit.parse(unit), base).setScale(QUANTITY_SCALE, RoundingMode.HALF_EVEN);
    }

    // Only ingredients of the company that owns the branch: a foreign ingredient id is treated
    // exactly like a missing one (no base unit to convert to).
    private Map<UUID, Unit> baseUnitsOf(UUID branchId, List<UUID> ingredientIds) {
        List<UUID> ids = ingredientIds.stream().distinct().toList();
        Map<UUID, Unit> baseUnits = new HashMap<>();
        UUID companyId = organizationApi.companyIdOf(branchId).orElse(null);
        if (companyId == null) {
            return baseUnits;
        }
        for (Ingredient ingredient : ingredientRepository.findAllById(ids)) {
            if (!ingredient.getCompanyId().equals(companyId)) {
                continue;
            }
            baseUnits.put(ingredient.getId(), Unit.parse(ingredient.getBaseUnit()));
        }
        return baseUnits;
    }

    private StockItem getOrCreate(UUID branchId, UUID ingredientId) {
        return lockOrCreate(branchId, ingredientId);
    }

    /**
     * Loads the stock row under a pessimistic write lock (SELECT ... FOR UPDATE) so concurrent
     * receipts/depletions of the same item serialize instead of losing updates. A missing row is
     * inserted with ON CONFLICT DO NOTHING first, so two first-ever writers both end up locking
     * the one row instead of one failing on the unique key.
     */
    public StockItem lockOrCreate(UUID branchId, UUID ingredientId) {
        return stockItemRepository.findForUpdate(ingredientId, branchId).orElseGet(() -> {
            entityManager.createNativeQuery(
                    "INSERT INTO stock_item (id, version, branch_id, ingredient_id, quantity_on_hand, "
                            + "reorder_level, avg_unit_cost, currency, created_at, updated_at) "
                            + "VALUES (gen_random_uuid(), 0, :branchId, :ingredientId, 0, 0, 0, :currency, now(), now()) "
                            + "ON CONFLICT (ingredient_id, branch_id) DO NOTHING")
                    .setParameter("branchId", branchId)
                    .setParameter("ingredientId", ingredientId)
                    .setParameter("currency", Money.DEFAULT_CURRENCY)
                    .executeUpdate();
            return stockItemRepository.findForUpdate(ingredientId, branchId).orElseThrow();
        });
    }

    // Weighted moving average: new cost = (value already on hand + value received) / total quantity.
    // e.g. 1000g @0.20 + 1000g @0.24 -> (200 + 240) / 2000 = 0.22 per unit.
    private Money newMovingAverage(BigDecimal existingQty, Money existingAvg, BigDecimal addedQty,
            Money addedCost, BigDecimal newQty) {
        if (existingQty.signum() <= 0 || newQty.signum() <= 0) {
            // Nothing (or a deficit) on hand: the old average prices stock that does not exist, so
            // blending it in would dilute the receipt's cost. The incoming cost is the average.
            return addedCost;
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
