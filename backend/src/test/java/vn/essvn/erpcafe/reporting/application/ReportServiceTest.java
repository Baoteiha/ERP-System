package vn.essvn.erpcafe.reporting.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import vn.essvn.erpcafe.common.context.BranchContext;
import vn.essvn.erpcafe.common.context.TenantContext;
import vn.essvn.erpcafe.identity.api.IdentityApi;
import vn.essvn.erpcafe.organization.api.BranchDto;
import vn.essvn.erpcafe.organization.api.OrganizationApi;
import vn.essvn.erpcafe.reporting.application.ReportModels.PeriodSummary;
import vn.essvn.erpcafe.reporting.persistence.BranchTotals;
import vn.essvn.erpcafe.reporting.persistence.DailyBranchFiguresRepository;

/**
 * Class: ReportServiceTest
 * Purpose: Proves the margin arithmetic and the report days a period covers, against a stubbed read model.
 */
class ReportServiceTest {

    private static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");

    private final DailyBranchFiguresRepository figures = mock(DailyBranchFiguresRepository.class);
    private final OrganizationApi organizationApi = mock(OrganizationApi.class);
    private final IdentityApi identityApi = mock(IdentityApi.class);
    private final ReportService service = new ReportService(figures, organizationApi, identityApi,
            new ReportingProperties(VIETNAM, 366));

    private final UUID branchId = UUID.randomUUID();
    private final Instant from = Instant.parse("2026-03-01T00:00:00Z");
    private final Instant to = Instant.parse("2026-03-02T00:00:00Z");

    private final UUID companyId = UUID.randomUUID();

    private void stub(UUID branch, String net, String cogs, String labor) {
        when(figures.totalsByBranch(anyCollection(), any(), any())).thenReturn(List.of(new BranchTotals(branch, 1,
                new BigDecimal("50000"), new BigDecimal("8000"), new BigDecimal(net), new BigDecimal("4200"),
                new BigDecimal(cogs), new BigDecimal(labor))));
    }

    private PeriodSummary summaryOfTheBranch(Instant periodFrom, Instant periodTo) {
        BranchContext.set(branchId);
        try {
            return TenantContext.runAs(companyId, () -> service.branchSummary(periodFrom, periodTo));
        } finally {
            BranchContext.clear();
        }
    }

    @Test
    void grossMarginIsNetSalesMinusCogs_netMarginSubtractsLabour() {
        stub(branchId, "42000", "10000", "7000");

        PeriodSummary summary = summaryOfTheBranch(from, to);

        assertThat(summary.grossMargin()).isEqualByComparingTo("32000");
        assertThat(summary.netMargin()).isEqualByComparingTo("25000");
        assertThat(summary.cogs()).isEqualByComparingTo("10000");
        assertThat(summary.grandTotal()).isEqualByComparingTo("46200");
    }

    @Test
    void aPeriod_coversEveryReportDayItTouches() {
        stub(branchId, "42000", "10000", "7000");

        // 00:00 UTC is 07:00 in Vietnam: 1 March 07:00 to 2 March 07:00 touches 1 and 2 March
        summaryOfTheBranch(from, to);
        // a period that ends just as a day starts leaves that day out
        summaryOfTheBranch(LocalDate.parse("2026-03-01").atStartOfDay(VIETNAM).toInstant(),
                LocalDate.parse("2026-03-02").atStartOfDay(VIETNAM).toInstant());

        verify(figures).totalsByBranch(List.of(branchId), LocalDate.parse("2026-03-01"), LocalDate.parse("2026-03-03"));
        verify(figures).totalsByBranch(List.of(branchId), LocalDate.parse("2026-03-01"), LocalDate.parse("2026-03-02"));
    }

    @Test
    void aBranchWithNoFigures_reportsZeros() {
        when(figures.totalsByBranch(anyCollection(), any(), any())).thenReturn(List.of());

        PeriodSummary summary = summaryOfTheBranch(from, to);

        assertThat(summary.orderCount()).isZero();
        assertThat(summary.netSales()).isZero();
        assertThat(summary.netMargin()).isZero();
    }

    @Test
    void outletsAreFilteredToTheBranchesWhereTheCallerReadsReports() {
        UUID other = UUID.randomUUID();
        UUID cashierAt = UUID.randomUUID();
        stub(branchId, "42000", "10000", "0");
        when(identityApi.hasPermissionAt(branchId, "report:read")).thenReturn(true);
        when(organizationApi.listActiveBranches(companyId)).thenReturn(List.of(
                new BranchDto(branchId, companyId, "Mine", "M", true, BigDecimal.ZERO),
                new BranchDto(cashierAt, companyId, "Till only", "C", true, BigDecimal.ZERO),
                new BranchDto(other, companyId, "Theirs", "T", true, BigDecimal.ZERO)));

        assertThat(TenantContext.runAs(companyId, () -> service.outletComparison(from, to)))
                .extracting(b -> b.branchId()).containsExactly(branchId);
        verify(figures).totalsByBranch(List.of(branchId), LocalDate.parse("2026-03-01"), LocalDate.parse("2026-03-03"));
    }
}
