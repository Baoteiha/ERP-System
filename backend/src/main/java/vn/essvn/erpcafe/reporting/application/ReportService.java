package vn.essvn.erpcafe.reporting.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.essvn.erpcafe.common.context.BranchContext;
import vn.essvn.erpcafe.identity.security.AuthPrincipal;
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
@EnableConfigurationProperties(ReportingProperties.class)
@Transactional(readOnly = true)
public class ReportService {

    private static final String REPORT_READ = "report:read";

    private final SalesApi salesApi;
    private final InventoryApi inventoryApi;
    private final StaffApi staffApi;
    private final OrganizationApi organizationApi;
    private final CurrentUser currentUser;
    private final ReportingProperties properties;

    public ReportService(SalesApi salesApi, InventoryApi inventoryApi,
            StaffApi staffApi, OrganizationApi organizationApi, CurrentUser currentUser,
            ReportingProperties properties) {
        this.salesApi = salesApi;
        this.inventoryApi = inventoryApi;
        this.staffApi = staffApi;
        this.organizationApi = organizationApi;
        this.currentUser = currentUser;
        this.properties = properties;
    }

    /** Performance of the active branch over [from, to). */
    public PeriodSummary branchSummary(Instant from, Instant to) {
        return performance(BranchContext.require(), from, to);
    }

    /** Daily net sales series for the active branch, days cut in the reporting zone. */
    public List<DailySales> salesByDay(Instant from, Instant to) {
        return salesApi.salesByDay(BranchContext.require(), from, to, properties.zone());
    }

    /** Side-by-side performance of the caller's active branches where their role lets them read reports. */
    public List<BranchPerformance> outletComparison(Instant from, Instant to) {
        AuthPrincipal caller = currentUser.require();
        return organizationApi.listActiveBranches(caller.companyId()).stream()
                .filter(b -> caller.permissionsAt(b.id()).contains(REPORT_READ))
                .map(b -> new BranchPerformance(b.id(), b.name(), b.code(), performance(b.id(), from, to)))
                .toList();
    }

    private PeriodSummary performance(UUID branchId, Instant from, Instant to) {
        SalesSummary sales = salesApi.summary(branchId, from, to);
        BigDecimal cogs = inventoryApi.depletionCost(branchId, from, to);
        BigDecimal labor = staffApi.laborCost(branchId, from, to);
        BigDecimal grossMargin = sales.netSales().subtract(cogs);
        BigDecimal netMargin = grossMargin.subtract(labor);
        return new PeriodSummary(sales.orderCount(), sales.grossSales(), sales.discountTotal(),
                sales.netSales(), sales.taxTotal(), sales.grandTotal(), cogs,
                labor, grossMargin, netMargin);
    }
}
