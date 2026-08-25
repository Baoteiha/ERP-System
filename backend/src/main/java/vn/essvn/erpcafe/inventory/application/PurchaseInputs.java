package vn.essvn.erpcafe.inventory.application;

import java.math.BigDecimal;
import java.util.UUID;

/** Service-layer inputs for creating and receiving purchase orders. */
public final class PurchaseInputs {

    /** A line to order: ingredient, quantity, agreed unit cost. */
    public record LineInput(UUID ingredientId, BigDecimal orderedQty, BigDecimal unitCost) {
    }

    /**
     * A receipt against a PO line: how much arrived and, optionally, the actual unit cost.
     * {@code unit} is the unit the delivery was counted in — any unit of the ingredient's
     * dimension (ADR-0006); blank/null means quantities (and the cost override) are
     * already in the ingredient's base unit. When given, {@code unitCostOverride} is per
     * {@code unit}, not per base unit.
     */
    public record ReceiptInput(UUID lineId, BigDecimal receivedQty, String unit, BigDecimal unitCostOverride) {
    }

    private PurchaseInputs() {
    }
}
