package vn.essvn.erpcafe.reporting.application;

import java.math.BigDecimal;
import java.util.UUID;

/** Reporting's composed result shapes. */
public final class ReportModels {

    private ReportModels() {
    }

    /**
     * One branch's performance over a period. Two COGS views: the order
     * snapshot captured at completion, and the inventory ledger's depletion
     * cost — margins use the ledger view (it also reflects reversals).
     */
    public record PeriodSummary(
            long orderCount,
            BigDecimal grossSales,
            BigDecimal discountTotal,
            BigDecimal taxTotal,
            BigDecimal revenue,
            BigDecimal cogsOrders,
            BigDecimal cogsDepletion,
            BigDecimal laborCost,
            BigDecimal grossMargin,
            BigDecimal netMargin) {
    }

    public record BranchPerformance(UUID branchId, String name, String code, PeriodSummary summary) {
    }
}
