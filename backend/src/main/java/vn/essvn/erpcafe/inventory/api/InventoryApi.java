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

    /** Whether the ingredient exists (and is not deleted). */
    boolean ingredientExists(UUID ingredientId);

    /**
     * Deducts the given ingredient quantities from stock at a branch and returns
     * the COGS (Σ quantity × moving-average unit cost). Idempotent per
     * {@code (refType, refId)} — a repeated call for the same reference is a no-op
     * and returns the already-recorded COGS. Negative stock is allowed (warn-but-allow).
     */
    CogsResult deductForOrder(UUID branchId, List<StockLine> lines, String refType, UUID refId);

    /**
     * Returns ingredient quantities to stock — the compensating movement when an
     * order is voided or refunded after its stock was already deducted. Idempotent
     * per {@code (refType, refId)}.
     */
    void returnForOrder(UUID branchId, List<StockLine> lines, String refType, UUID refId);

    /**
     * COGS measured from the stock ledger for {@code [from, to)} at a branch:
     * Σ sale-depletion cost − Σ sale-reversal cost. The depletion-side view of
     * COGS used by reporting (vs the order-snapshot view held by sales).
     */
    BigDecimal depletionCost(UUID branchId, Instant from, Instant to);
}
