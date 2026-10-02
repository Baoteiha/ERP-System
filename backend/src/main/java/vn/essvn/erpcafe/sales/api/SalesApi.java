package vn.essvn.erpcafe.sales.api;

import java.util.UUID;

/**
 * Published facade of the sales module: what other modules need to ask of it.
 */
public interface SalesApi {

    /** Whether the branch has an OPEN order; legacy PAID orders never completed do not count. */
    boolean hasOpenOrders(UUID branchId);
}
