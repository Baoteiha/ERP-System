package vn.essvn.erpcafe.inventory.application;

import java.math.BigDecimal;
import java.util.UUID;

/** Service-layer inputs for creating and receiving purchase orders. */
public final class PurchaseInputs {

    /**
     * A line to order: a supplier item, plus the quantity and agreed cost in its ordering unit (its pack,
     * or its unit). {@code ingredientId} and {@code unit} exist only so a request carrying them is refused.
     */
    public record LineInput(UUID itemId, UUID ingredientId, String unit, BigDecimal orderedQty,
            BigDecimal unitCost) {
    }

    /**
     * A receipt against a PO line: how much arrived and, optionally, the actual unit cost.
     * {@code unit} is the unit the delivery was counted in — any unit of the ingredient's
     * dimension (ADR-0006); blank/null means the line's own unit. When given, {@code unitCostOverride} is per
     * {@code unit}, not per base unit.
     */
    public record ReceiptInput(UUID lineId, BigDecimal receivedQty, String unit, BigDecimal unitCostOverride) {
    }

    private PurchaseInputs() {
    }
}
