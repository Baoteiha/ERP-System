package vn.essvn.erpcafe.sales.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Class: OrderReversed
 * Purpose: Announces that a sale was voided or refunded, with the positive figures it takes back on the day of the reversal.
 */
public record OrderReversed(
        UUID orderId,
        UUID companyId,
        UUID branchId,
        Instant occurredAt,
        BigDecimal grossSales,
        BigDecimal discountTotal,
        BigDecimal netSales,
        BigDecimal taxTotal,
        BigDecimal cogs) {
}
