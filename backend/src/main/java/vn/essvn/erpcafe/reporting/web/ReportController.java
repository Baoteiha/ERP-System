package vn.essvn.erpcafe.reporting.web;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import vn.essvn.erpcafe.common.web.ApiVersions;
import vn.essvn.erpcafe.reporting.application.ReportModels.BranchPerformance;
import vn.essvn.erpcafe.reporting.application.ReportModels.PeriodSummary;
import vn.essvn.erpcafe.reporting.application.ReportService;
import vn.essvn.erpcafe.sales.api.DailySales;

/**
 * Cross-branch analytics. Summary and daily series act on the active branch
 * ({@code X-Branch-Id}); the outlet comparison spans all active branches.
 * Defaults to the last 7 days when no range is given.
 */
@RestController
@RequestMapping(ApiVersions.V1 + "/reports")
public class ReportController {

    private final ReportService reportService;

    public ReportController(ReportService reportService) {
        this.reportService = reportService;
    }

    @GetMapping("/summary")
    @PreAuthorize("hasAuthority('report:read')")
    public PeriodSummary summary(
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to) {
        Instant end = to != null ? to : Instant.now();
        Instant start = from != null ? from : end.minus(7, ChronoUnit.DAYS);
        return reportService.branchSummary(start, end);
    }

    @GetMapping("/sales-by-day")
    @PreAuthorize("hasAuthority('report:read')")
    public List<DailySales> salesByDay(
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to) {
        Instant end = to != null ? to : Instant.now();
        Instant start = from != null ? from : end.minus(7, ChronoUnit.DAYS);
        return reportService.salesByDay(start, end);
    }

    @GetMapping("/outlets")
    @PreAuthorize("hasAuthority('report:read')")
    public List<BranchPerformance> outlets(
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to) {
        Instant end = to != null ? to : Instant.now();
        Instant start = from != null ? from : end.minus(7, ChronoUnit.DAYS);
        return reportService.outletComparison(start, end);
    }
}
