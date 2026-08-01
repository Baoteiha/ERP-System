package vn.essvn.erpcafe.sales.api;

import java.math.BigDecimal;

/**
 * Revenue totals over a period. {@code cogsTotal} is the order-snapshot COGS
 * captured at completion (reporting may contrast it with the inventory
 * ledger's depletion-side COGS).
 */
public record SalesSummary(
        long orderCount,
        BigDecimal grossSales,
        BigDecimal discountTotal,
        BigDecimal taxTotal,
        BigDecimal revenue,
        BigDecimal cogsTotal) {
}
