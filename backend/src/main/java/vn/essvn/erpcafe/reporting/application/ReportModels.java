package vn.essvn.erpcafe.reporting.application;

import java.math.BigDecimal;
import java.util.UUID;

/** Reporting's composed result shapes. */
public final class ReportModels {

    private ReportModels() {
    }

    /**
     * One branch's performance over a period. {@code cogs} is the inventory
     * ledger's depletion cost net of reversals; gross margin is net sales less
     * cogs and net margin is gross margin less labour.
     */
    public record PeriodSummary(
            long orderCount,
            BigDecimal grossSales,
            BigDecimal discountTotal,
            BigDecimal netSales,
            BigDecimal taxTotal,
            BigDecimal grandTotal,
            BigDecimal cogs,
            BigDecimal laborCost,
            BigDecimal grossMargin,
            BigDecimal netMargin) {
    }

    public record BranchPerformance(UUID branchId, String name, String code, PeriodSummary summary) {
    }
}
