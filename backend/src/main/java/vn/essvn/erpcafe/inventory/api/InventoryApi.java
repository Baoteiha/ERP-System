package vn.essvn.erpcafe.inventory.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Published facade of the inventory module. The sales module uses this to deduct
 * ingredient stock when an order completes and to capture COGS — without
 * touching inventory internals.
 */
public interface InventoryApi {

    /** Whether any ingredient at the branch has a non-zero quantity on hand (negative counts). */
    boolean hasStockOnHand(UUID branchId);

    /**
     * Checks that every line could be consumed from the company's stock: the ingredient exists in
     * that company (a foreign or deleted one counts as missing), the unit is known, and it measures
     * the same thing as the ingredient's base unit. Throws a business-rule error listing each problem.
     */
    void validateConsumption(UUID companyId, List<StockLine> lines);

    /**
     * Deducts the given ingredient quantities from stock at a branch and returns
     * the COGS (Σ quantity × moving-average unit cost). Idempotent per
     * {@code (refType, refId)} — a repeated call for the same reference is a no-op
     * and returns the already-recorded COGS. Negative stock is allowed (warn-but-allow).
     */
    CogsResult deductForOrder(UUID branchId, List<StockLine> lines, String refType, UUID refId);

    /**
     * Reverses what a past deduction recorded: one reversal per SALE_DEPLETION movement of
     * {@code (refType, refId)}, same ingredient, same quantity, same unit cost, written under
     * {@code (reversalRefType, refId)}. Returns the reversed cost. Idempotent per reversal
     * reference — a repeat call is a no-op returning zero cost.
     */
    CogsResult reverseDeduction(UUID branchId, String refType, UUID refId, String reversalRefType);

    /**
     * COGS measured from the stock ledger for {@code [from, to)} at a branch:
     * Σ sale-depletion cost − Σ sale-reversal cost. The depletion-side view of
     * COGS used by reporting (vs the order-snapshot view held by sales).
     */
    BigDecimal depletionCost(UUID branchId, Instant from, Instant to);
}
