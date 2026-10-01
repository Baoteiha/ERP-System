package vn.essvn.erpcafe.staff.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * Published facade of the staff module. Reporting uses this to fold labor cost
 * into branch performance without touching staff internals.
 */
public interface StaffApi {

    /** Whether someone is clocked in at the company's branch right now. */
    boolean hasOpenShifts(UUID companyId, UUID branchId);

    /**
     * Total labor cost of the COMPLETED shifts at a branch whose clock-out time
     * falls in {@code [from, to)}: a shift counts in the period it ended, not the
     * one it started in. Amounts are in the default currency. A branch that is not
     * the company's is reported as not found.
     */
    BigDecimal laborCost(UUID companyId, UUID branchId, Instant from, Instant to);

    /**
     * {@link #laborCost} for several branches at once, with the same rule. Every
     * requested branch has an entry (zero when it has no completed shifts); a
     * branch that is not the company's is reported as not found.
     */
    Map<UUID, BigDecimal> laborCostByBranch(UUID companyId, Collection<UUID> branchIds, Instant from, Instant to);
}
