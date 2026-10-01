package vn.essvn.erpcafe.sales.api;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

/**
 * Published facade of the sales module: sales aggregates for reporting.
 * Amounts come from COMPLETED orders (and legacy PAID ones); cancelled, void
 * and refunded orders are excluded. Net sales exclude tax. Days are cut in the
 * zone the caller passes.
 */
public interface SalesApi {

    /** Totals for a branch over {@code [from, to)}. */
    SalesSummary summary(UUID branchId, Instant from, Instant to);

    /** Net sales per calendar day in {@code zone} for a branch over {@code [from, to)}. */
    List<DailySales> salesByDay(UUID branchId, Instant from, Instant to, ZoneId zone);
}
