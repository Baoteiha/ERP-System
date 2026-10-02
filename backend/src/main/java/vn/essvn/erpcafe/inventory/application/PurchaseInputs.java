package vn.essvn.erpcafe.inventory.application;

import java.math.BigDecimal;
import java.util.UUID;

/** Service-layer inputs for creating and receiving purchase orders. */
public final class PurchaseInputs {

    /**
     * A line to order: either an item (which fixes ingredient and unit) or an ingredient with a
     * unit (blank = the ingredient's base unit), plus the quantity and the agreed cost, both per
     * that unit.
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
