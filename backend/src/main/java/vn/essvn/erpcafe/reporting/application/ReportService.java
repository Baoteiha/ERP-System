package vn.essvn.erpcafe.reporting.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.essvn.erpcafe.common.context.BranchContext;
import vn.essvn.erpcafe.identity.security.CurrentUser;
import vn.essvn.erpcafe.inventory.api.InventoryApi;
import vn.essvn.erpcafe.organization.api.BranchDto;
import vn.essvn.erpcafe.organization.api.OrganizationApi;
import vn.essvn.erpcafe.reporting.application.ReportModels.BranchPerformance;
import vn.essvn.erpcafe.reporting.application.ReportModels.PeriodSummary;
import vn.essvn.erpcafe.sales.api.DailySales;
import vn.essvn.erpcafe.sales.api.SalesApi;
import vn.essvn.erpcafe.sales.api.SalesSummary;
import vn.essvn.erpcafe.staff.api.StaffApi;

/**
 * Cross-module analytics, composed exclusively from other modules' published
 * APIs (sales revenue, inventory depletion COGS, staff labor cost, org branch
 * list) — reporting owns no tables of its own.
 */
@Service
@Transactional(readOnly = true)
public class ReportService {

    private final SalesApi salesApi;
    private final InventoryApi inventoryApi;
    private final StaffApi staffApi;
    private final OrganizationApi organizationApi;
    private final CurrentUser currentUser;

    public ReportService(SalesApi salesApi, InventoryApi inventoryApi,
            StaffApi staffApi, OrganizationApi organizationApi, CurrentUser currentUser) {
        this.salesApi = salesApi;
        this.inventoryApi = inventoryApi;
        this.staffApi = staffApi;
        this.organizationApi = organizationApi;
        this.currentUser = currentUser;
    }

    /** Performance of the active branch over [from, to). */
    public PeriodSummary branchSummary(Instant from, Instant to) {
        return performance(BranchContext.require(), from, to);
    }

    /** Daily revenue series for the active branch. */
    public List<DailySales> salesByDay(Instant from, Instant to) {
        return salesApi.salesByDay(BranchContext.require(), from, to);
    }

    /** Side-by-side performance of every active branch (outlet comparison). */
    public List<BranchPerformance> outletComparison(Instant from, Instant to) {
        return organizationApi.listActiveBranches(currentUser.require().companyId()).stream()
                .map(b -> new BranchPerformance(b.id(), b.name(), b.code(), performance(b.id(), from, to)))
                .toList();
    }

    private PeriodSummary performance(UUID branchId, Instant from, Instant to) {
        SalesSummary sales = salesApi.summary(branchId, from, to);
        BigDecimal cogsDepletion = inventoryApi.depletionCost(branchId, from, to);
        BigDecimal labor = staffApi.laborCost(branchId, from, to);
        BigDecimal grossMargin = sales.revenue().subtract(cogsDepletion);
        BigDecimal netMargin = grossMargin.subtract(labor);
        return new PeriodSummary(sales.orderCount(), sales.grossSales(), sales.discountTotal(),
                sales.taxTotal(), sales.revenue(), sales.cogsTotal(), cogsDepletion,
                labor, grossMargin, netMargin);
    }
}
