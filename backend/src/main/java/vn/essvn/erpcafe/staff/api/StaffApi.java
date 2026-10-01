package vn.essvn.erpcafe.staff.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Published facade of the staff module. Reporting uses this to fold labor cost
 * into branch performance without touching staff internals.
 */
public interface StaffApi {

    /** Whether someone is clocked in at the branch right now. */
    boolean hasOpenShifts(UUID branchId);

    /**
     * Total labor cost captured by shifts completed in {@code [from, to)} at a
     * branch, counted in the period of the clock-out time. Amounts are in the
     * default currency.
     */
    BigDecimal laborCost(UUID branchId, Instant from, Instant to);
}
