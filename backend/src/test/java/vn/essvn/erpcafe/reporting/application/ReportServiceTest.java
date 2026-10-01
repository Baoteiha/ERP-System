package vn.essvn.erpcafe.reporting.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import vn.essvn.erpcafe.common.context.BranchContext;
import vn.essvn.erpcafe.common.context.TenantContext;
import vn.essvn.erpcafe.identity.security.AuthPrincipal;
import vn.essvn.erpcafe.identity.security.CurrentUser;
import vn.essvn.erpcafe.inventory.api.InventoryApi;
import vn.essvn.erpcafe.organization.api.BranchDto;
import vn.essvn.erpcafe.organization.api.OrganizationApi;
import vn.essvn.erpcafe.reporting.application.ReportModels.PeriodSummary;
import vn.essvn.erpcafe.sales.api.SalesApi;
import vn.essvn.erpcafe.sales.api.SalesSummary;
import vn.essvn.erpcafe.staff.api.StaffApi;

/**
 * Class: ReportServiceTest
 * Purpose: Proves the margin arithmetic against stubbed module facades.
 */
class ReportServiceTest {

    private final SalesApi salesApi = mock(SalesApi.class);
    private final InventoryApi inventoryApi = mock(InventoryApi.class);
    private final StaffApi staffApi = mock(StaffApi.class);
    private final OrganizationApi organizationApi = mock(OrganizationApi.class);
    private final CurrentUser currentUser = mock(CurrentUser.class);
    private final ReportService service = new ReportService(salesApi, inventoryApi, staffApi,
            organizationApi, currentUser, new ReportingProperties(ZoneId.of("Asia/Ho_Chi_Minh"), 366));

    private final UUID branchId = UUID.randomUUID();
    private final Instant from = Instant.parse("2026-03-01T00:00:00Z");
    private final Instant to = Instant.parse("2026-03-02T00:00:00Z");

    private final UUID companyId = UUID.randomUUID();
    // the company comes from the tenant context; a principal naming another one must not matter
    private final UUID notTheTenant = UUID.randomUUID();

    @BeforeEach
    void enterTenant() {
        TenantContext.set(companyId);
    }

    @AfterEach
    void leaveTenant() {
        TenantContext.clear();
    }

    private void stub(UUID branch, String net, String cogs, String labor) {
        when(salesApi.summary(branch, from, to)).thenReturn(new SalesSummary(1, new BigDecimal("50000"),
                new BigDecimal("8000"), new BigDecimal(net), new BigDecimal("4200"), new BigDecimal("46200")));
        when(inventoryApi.depletionCost(branch, from, to)).thenReturn(new BigDecimal(cogs));
        when(staffApi.laborCost(companyId, branch, from, to)).thenReturn(new BigDecimal(labor));
        when(staffApi.laborCostByBranch(eq(companyId), anyCollection(), eq(from), eq(to)))
                .thenReturn(Map.of(branch, new BigDecimal(labor)));
    }

    @Test
    void grossMarginIsNetSalesMinusCogs_netMarginSubtractsLabour() {
        stub(branchId, "42000", "10000", "7000");
        BranchContext.set(branchId);
        when(currentUser.require()).thenReturn(new AuthPrincipal(UUID.randomUUID(), "a@b", notTheTenant,
                Map.of(), Set.of(), Set.of()));
        try {
            PeriodSummary summary = service.branchSummary(from, to);
            assertThat(summary.grossMargin()).isEqualByComparingTo("32000");
            assertThat(summary.netMargin()).isEqualByComparingTo("25000");
            assertThat(summary.cogs()).isEqualByComparingTo("10000");
        } finally {
            BranchContext.clear();
        }
    }

    @Test
    void outletsAreFilteredToTheBranchesWhereTheCallerReadsReports() {
        UUID other = UUID.randomUUID();
        UUID cashierAt = UUID.randomUUID();
        stub(branchId, "42000", "10000", "0");
        when(currentUser.require()).thenReturn(new AuthPrincipal(UUID.randomUUID(), "a@b", notTheTenant,
                Map.of(branchId, Set.of("report:read"), cashierAt, Set.of("sales:write")), Set.of(), Set.of()));
        when(organizationApi.listActiveBranches(companyId)).thenReturn(java.util.List.of(
                new BranchDto(branchId, companyId, "Mine", "M", true, BigDecimal.ZERO),
                new BranchDto(cashierAt, companyId, "Till only", "C", true, BigDecimal.ZERO),
                new BranchDto(other, companyId, "Theirs", "T", true, BigDecimal.ZERO)));

        assertThat(service.outletComparison(from, to)).extracting(b -> b.branchId()).containsExactly(branchId);
    }
}
