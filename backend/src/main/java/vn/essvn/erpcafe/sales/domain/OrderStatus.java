package vn.essvn.erpcafe.sales.domain;

/**
 * Order lifecycle:
 * <pre>
 *   OPEN ──pay──▶ PAID ──complete──▶ COMPLETED
 *    │                                  │
 *    └──cancel──▶ CANCELLED             ├──void──▶ VOID
 *                                       └──refund──▶ REFUNDED
 * </pre>
 * Stock is deducted exactly once, on the transition to COMPLETED; VOID/REFUND
 * write compensating movements to return it.
 */
public enum OrderStatus {
    OPEN,
    PAID,
    COMPLETED,
    CANCELLED,
    VOID,
    REFUNDED
}
