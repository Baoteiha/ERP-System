package vn.essvn.erpcafe.sales.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Class: OrderCompleted
 * Purpose: Announces a completed sale with the figures reports book on its completion day, and, for a legacy paid order completed late, the instant it was booked at before.
 */
public record OrderCompleted(
        UUID orderId,
        UUID companyId,
        UUID branchId,
        Instant occurredAt,
        BigDecimal grossSales,
        BigDecimal discountTotal,
        BigDecimal netSales,
        BigDecimal taxTotal,
        BigDecimal cogs,
        Instant previouslyBookedAt) {
}
