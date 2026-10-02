package vn.essvn.erpcafe.reporting.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.essvn.erpcafe.common.context.BranchContext;
import vn.essvn.erpcafe.common.context.TenantContext;
import vn.essvn.erpcafe.identity.api.IdentityApi;
import vn.essvn.erpcafe.organization.api.BranchDto;
import vn.essvn.erpcafe.organization.api.OrganizationApi;
import vn.essvn.erpcafe.reporting.application.ReportModels.BranchPerformance;
import vn.essvn.erpcafe.reporting.application.ReportModels.PeriodSummary;
import vn.essvn.erpcafe.reporting.persistence.BranchTotals;
import vn.essvn.erpcafe.reporting.persistence.DailyBranchFiguresRepository;
import vn.essvn.erpcafe.sales.api.DailySales;

/**
 * Cross-module analytics, read from reporting's own daily figures per branch, which
 * {@link ReportProjection} keeps from the sales and shifts other modules announce. The branch list
 * and the caller's permissions still come from those modules' published APIs.
 */
@Service
@EnableConfigurationProperties(ReportingProperties.class)
@Transactional(readOnly = true)
public class ReportService {

    private static final String REPORT_READ = "report:read";
    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final PeriodSummary NOTHING = new PeriodSummary(0, ZERO, ZERO, ZERO, ZERO, ZERO, ZERO, ZERO,
            ZERO, ZERO);

    private final DailyBranchFiguresRepository figures;
    private final OrganizationApi organizationApi;
    private final IdentityApi identityApi;
    private final ReportingProperties properties;

    public ReportService(DailyBranchFiguresRepository figures, OrganizationApi organizationApi,
            IdentityApi identityApi, ReportingProperties properties) {
        this.figures = figures;
        this.organizationApi = organizationApi;
        this.identityApi = identityApi;
        this.properties = properties;
    }

    /** Performance of the active branch over the report days [from, to) touches. */
    public PeriodSummary branchSummary(Instant from, Instant to) {
        UUID branchId = BranchContext.require();
        return summaries(List.of(branchId), from, to).get(branchId);
    }

    /** Daily net sales of the active branch: each report day [from, to) touches that saw a sale or a reversal. */
    public List<DailySales> salesByDay(Instant from, Instant to) {
        ReportDays days = days(from, to);
        return figures.salesDays(BranchContext.require(), days.first(), days.end()).stream()
                .map(day -> new DailySales(day.getDay(), day.getOrderCount(), day.getNetSales()))
                .toList();
    }

    /** Side-by-side performance of the caller's active branches where their role lets them read reports. */
    public List<BranchPerformance> outletComparison(Instant from, Instant to) {
        UUID companyId = TenantContext.require();
        List<BranchDto> branches = organizationApi.listActiveBranches(companyId).stream()
                .filter(b -> identityApi.hasPermissionAt(b.id(), REPORT_READ))
                .toList();
        if (branches.isEmpty()) {
            return List.of();
        }
        Map<UUID, PeriodSummary> summaries = summaries(branches.stream().map(BranchDto::id).toList(), from, to);
        return branches.stream()
                .map(b -> new BranchPerformance(b.id(), b.name(), b.code(), summaries.get(b.id())))
                .toList();
    }

    /** One read for every branch asked about; a branch with no figures in the period reports zeros. */
    private Map<UUID, PeriodSummary> summaries(Collection<UUID> branchIds, Instant from, Instant to) {
        ReportDays days = days(from, to);
        Map<UUID, PeriodSummary> result = new HashMap<>();
        branchIds.forEach(id -> result.put(id, NOTHING));
        figures.totalsByBranch(branchIds, days.first(), days.end())
                .forEach(totals -> result.put(totals.branchId(), summary(totals)));
        return result;
    }

    private static PeriodSummary summary(BranchTotals totals) {
        BigDecimal grossMargin = totals.netSales().subtract(totals.cogs());
        return new PeriodSummary(totals.orderCount(), totals.grossSales(), totals.discountTotal(),
                totals.netSales(), totals.taxTotal(), totals.netSales().add(totals.taxTotal()), totals.cogs(),
                totals.laborCost(), grossMargin, grossMargin.subtract(totals.laborCost()));
    }

    /** Report days {@code [first, end)}. */
    private record ReportDays(LocalDate first, LocalDate end) {
    }

    /**
     * Figures are kept per report day, so a period is reported as whole days: every day in the
     * reporting zone that [from, to) touches. A {@code to} at the very start of a day leaves that day out.
     */
    private ReportDays days(Instant from, Instant to) {
        ZoneId zone = properties.zone();
        LocalDate last = LocalDate.ofInstant(to, zone);
        LocalDate end = last.atStartOfDay(zone).toInstant().equals(to) ? last : last.plusDays(1);
        return new ReportDays(LocalDate.ofInstant(from, zone), end);
    }
}
