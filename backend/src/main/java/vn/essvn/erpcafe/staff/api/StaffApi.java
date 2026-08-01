package vn.essvn.erpcafe.staff.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Published facade of the staff module. Reporting uses this to fold labor cost
 * into branch performance without touching staff internals.
 */
public interface StaffApi {

    /**
     * Total labor cost captured by shifts completed in {@code [from, to)} at a
     * branch. Amounts are in the default currency.
     */
    BigDecimal laborCost(UUID branchId, Instant from, Instant to);
}
