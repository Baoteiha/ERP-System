package vn.essvn.erpcafe.reporting.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.essvn.erpcafe.common.context.BranchContext;
import vn.essvn.erpcafe.common.context.TenantContext;
import vn.essvn.erpcafe.identity.api.IdentityApi;
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
    private final IdentityApi identityApi;
    private final ReportingProperties properties;

    public ReportService(SalesApi salesApi, InventoryApi inventoryApi,
            StaffApi staffApi, OrganizationApi organizationApi, IdentityApi identityApi,
            ReportingProperties properties) {
        this.salesApi = salesApi;
        this.inventoryApi = inventoryApi;
        this.staffApi = staffApi;
        this.organizationApi = organizationApi;
        this.identityApi = identityApi;
        this.properties = properties;
    }

    /** Performance of the active branch over [from, to). */
    public PeriodSummary branchSummary(Instant from, Instant to) {
        UUID branchId = BranchContext.require();
        UUID companyId = TenantContext.require();
        return performance(branchId, from, to, staffApi.laborCost(companyId, branchId, from, to));
    }

    /** Daily net sales series for the active branch, days cut in the reporting zone. */
    public List<DailySales> salesByDay(Instant from, Instant to) {
        return salesApi.salesByDay(BranchContext.require(), from, to, properties.zone());
    }

    /** Side-by-side performance of the caller's active branches where their role lets them read reports. */
    public List<BranchPerformance> outletComparison(Instant from, Instant to) {
        UUID companyId = TenantContext.require();
        List<BranchDto> branches = organizationApi.listActiveBranches(companyId).stream()
                .filter(b -> identityApi.hasPermissionAt(b.id(), REPORT_READ))
                .toList();
        Map<UUID, BigDecimal> labor = staffApi.laborCostByBranch(companyId,
                branches.stream().map(BranchDto::id).toList(), from, to);
        return branches.stream()
                .map(b -> new BranchPerformance(b.id(), b.name(), b.code(),
                        performance(b.id(), from, to, labor.get(b.id()))))
                .toList();
    }

    private PeriodSummary performance(UUID branchId, Instant from, Instant to, BigDecimal labor) {
        SalesSummary sales = salesApi.summary(branchId, from, to);
        BigDecimal cogs = inventoryApi.depletionCost(branchId, from, to);
        BigDecimal grossMargin = sales.netSales().subtract(cogs);
        BigDecimal netMargin = grossMargin.subtract(labor);
        return new PeriodSummary(sales.orderCount(), sales.grossSales(), sales.discountTotal(),
                sales.netSales(), sales.taxTotal(), sales.grandTotal(), cogs,
                labor, grossMargin, netMargin);
    }
}
