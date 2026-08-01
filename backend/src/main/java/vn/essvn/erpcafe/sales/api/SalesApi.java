package vn.essvn.erpcafe.sales.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Published facade of the sales module: revenue aggregates for reporting.
 * Revenue counts orders that took money and kept it (PAID or COMPLETED);
 * cancelled/void/refunded orders are excluded.
 */
public interface SalesApi {

    /** Totals for a branch over {@code [from, to)}. */
    SalesSummary summary(UUID branchId, Instant from, Instant to);

    /** Revenue per calendar day (UTC) for a branch over {@code [from, to)}. */
    List<DailySales> salesByDay(UUID branchId, Instant from, Instant to);
}
