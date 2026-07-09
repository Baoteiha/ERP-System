package vn.essvn.erpcafe.inventory.domain;

/** Lifecycle of a purchase order. */
public enum PurchaseOrderStatus {
    DRAFT,
    SENT,
    PARTIALLY_RECEIVED,
    RECEIVED,
    CANCELLED
}
