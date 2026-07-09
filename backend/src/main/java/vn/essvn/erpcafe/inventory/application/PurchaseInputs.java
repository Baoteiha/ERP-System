package vn.essvn.erpcafe.inventory.application;

import java.math.BigDecimal;
import java.util.UUID;

/** Service-layer inputs for creating and receiving purchase orders. */
public final class PurchaseInputs {

    /** A line to order: ingredient, quantity, agreed unit cost. */
    public record LineInput(UUID ingredientId, BigDecimal orderedQty, BigDecimal unitCost) {
    }

    /** A receipt against a PO line: how much arrived and, optionally, the actual unit cost. */
    public record ReceiptInput(UUID lineId, BigDecimal receivedQty, BigDecimal unitCostOverride) {
    }

    private PurchaseInputs() {
    }
}
