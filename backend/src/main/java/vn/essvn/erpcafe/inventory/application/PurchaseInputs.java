package vn.essvn.erpcafe.inventory.application;

import java.math.BigDecimal;
import java.util.UUID;

import vn.essvn.erpcafe.inventory.domain.OverDeliveryKind;

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
     * A delivery against a PO line. {@code receivedQty} is what was delivered: whole packs on a pack line,
     * otherwise in {@code unit} (any unit of the line's dimension, ADR-0006; blank = the line's unit).
     * {@code unitCostOverride} is the invoice price per pack, or per {@code unit}. {@code damagedQty} is in
     * the line's unit on a pack line, otherwise in {@code unit}. {@code overDeliveryKind} says how units
     * beyond the outstanding quantity are taken.
     */
    public record ReceiptInput(UUID lineId, BigDecimal receivedQty, String unit, BigDecimal unitCostOverride,
            BigDecimal damagedQty, OverDeliveryKind overDeliveryKind, String note) {
    }

    private PurchaseInputs() {
    }
}
