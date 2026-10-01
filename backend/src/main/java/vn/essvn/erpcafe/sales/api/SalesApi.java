package vn.essvn.erpcafe.sales.api;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

/**
 * Published facade of the sales module: sales aggregates for reporting.
 * Sales are booked when they complete (legacy PAID orders at creation); voids
 * and refunds are subtracted in the period of the reversal, so a period's
 * figures can be negative. Cancelled orders never count. {@code orderCount} is
 * the sales made, not netted of reversals. Net sales exclude tax. Days are cut
 * in the zone the caller passes.
 */
public interface SalesApi {

    /** Totals for a branch over {@code [from, to)}. */
    SalesSummary summary(UUID branchId, Instant from, Instant to);

    /** Net sales per calendar day in {@code zone} for a branch over {@code [from, to)}. */
    List<DailySales> salesByDay(UUID branchId, Instant from, Instant to, ZoneId zone);
}
