package vn.essvn.erpcafe.sales.api;

import java.math.BigDecimal;

/**
 * Sales totals over a period. {@code grossSales} is before any discount,
 * {@code netSales} is gross less discounts (tax excluded), {@code grandTotal}
 * is net plus tax.
 */
public record SalesSummary(
        long orderCount,
        BigDecimal grossSales,
        BigDecimal discountTotal,
        BigDecimal netSales,
        BigDecimal taxTotal,
        BigDecimal grandTotal) {
}
