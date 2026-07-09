package vn.essvn.erpcafe.inventory.domain;

/**
 * The kind of stock movement recorded in the append-only ledger. The sign of a
 * movement's quantity follows its type (receipts/transfer-in positive;
 * depletion/waste/transfer-out negative; adjustments either way).
 */
public enum MovementType {
    PURCHASE_RECEIPT,
    SALE_DEPLETION,
    SALE_REVERSAL,   // stock returned when an order is voided/refunded
    WASTE,
    ADJUSTMENT,
    TRANSFER_IN,
    TRANSFER_OUT
}
