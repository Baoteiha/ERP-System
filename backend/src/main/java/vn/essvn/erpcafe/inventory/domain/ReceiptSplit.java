package vn.essvn.erpcafe.inventory.domain;

import java.math.BigDecimal;

import vn.essvn.erpcafe.common.exception.BusinessRuleException;

/**
 * Class: ReceiptSplit
 * Purpose: Splits one delivered purchase line into what the order counts, what enters stock paid and free, and what was lost.
 */
public record ReceiptSplit(BigDecimal countedQty, BigDecimal growOrderedBy, BigDecimal overDeliveredQty,
        BigDecimal chargedIntoStock, BigDecimal freeIntoStock, BigDecimal lossQty) {

    /**
     * Splits a delivery. {@code delivered} and {@code outstanding} are in the line's ordering unit (packs,
     * or its unit); {@code damaged} is in the line's unit; {@code kind} is required exactly when more
     * arrived than was outstanding.
     */
    public static ReceiptSplit of(BigDecimal delivered, BigDecimal outstanding, BigDecimal unitsPerOrderingUnit,
            OverDeliveryKind kind, BigDecimal damaged) {
        BigDecimal over = delivered.subtract(outstanding).max(BigDecimal.ZERO);
        if (over.signum() > 0 && kind == null) {
            throw new BusinessRuleException("%s more than outstanding arrived: choose charged or free gift"
                    .formatted(over.stripTrailingZeros().toPlainString()));
        }
        if (over.signum() == 0 && kind != null) {
            throw new BusinessRuleException(
                    "Nothing arrived beyond what was ordered, so there is no extra to charge or take free");
        }
        BigDecimal freeOrdering = kind == OverDeliveryKind.FREE ? over : BigDecimal.ZERO;
        BigDecimal chargedUnits = delivered.subtract(freeOrdering).multiply(unitsPerOrderingUnit);
        BigDecimal freeUnits = freeOrdering.multiply(unitsPerOrderingUnit);
        if (damaged.signum() < 0 || damaged.compareTo(chargedUnits.add(freeUnits)) > 0) {
            throw new BusinessRuleException("Damaged cannot exceed what was delivered");
        }
        // Damage is counted against what was paid for first, so the loss is never understated.
        BigDecimal damagedCharged = damaged.min(chargedUnits);
        BigDecimal damagedFree = damaged.subtract(damagedCharged);
        boolean charged = kind == OverDeliveryKind.CHARGED;
        return new ReceiptSplit(
                charged ? delivered : delivered.subtract(freeOrdering),
                charged ? over : BigDecimal.ZERO,
                over,
                chargedUnits.subtract(damagedCharged),
                freeUnits.subtract(damagedFree),
                damagedCharged);
    }
}
