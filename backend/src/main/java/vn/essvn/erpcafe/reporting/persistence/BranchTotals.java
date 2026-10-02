package vn.essvn.erpcafe.reporting.persistence;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Class: BranchTotals
 * Purpose: A branch's daily figures summed over a run of report days.
 */
public record BranchTotals(
        UUID branchId,
        long orderCount,
        BigDecimal grossSales,
        BigDecimal discountTotal,
        BigDecimal netSales,
        BigDecimal taxTotal,
        BigDecimal cogs,
        BigDecimal laborCost) {
}
