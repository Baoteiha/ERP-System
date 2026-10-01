package vn.essvn.erpcafe.reporting.web;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import vn.essvn.erpcafe.common.exception.BadRequestException;
import vn.essvn.erpcafe.common.web.ApiVersions;
import vn.essvn.erpcafe.reporting.application.ReportModels.BranchPerformance;
import vn.essvn.erpcafe.reporting.application.ReportModels.PeriodSummary;
import vn.essvn.erpcafe.reporting.application.ReportService;
import vn.essvn.erpcafe.reporting.application.ReportingProperties;
import vn.essvn.erpcafe.sales.api.DailySales;

/**
 * Cross-branch analytics. Summary and daily series act on the active branch
 * ({@code X-Branch-Id}); the outlet comparison spans the caller's branches.
 * Without a range the period is today in the reporting zone.
 */
@RestController
@RequestMapping(ApiVersions.V1 + "/reports")
public class ReportController {

    private final ReportService reportService;
    private final ReportingProperties properties;

    public ReportController(ReportService reportService, ReportingProperties properties) {
        this.reportService = reportService;
        this.properties = properties;
    }

    @GetMapping("/summary")
    @PreAuthorize("hasAuthority('report:read')")
    public PeriodSummary summary(
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to) {
        Period period = period(from, to);
        return reportService.branchSummary(period.from(), period.to());
    }

    @GetMapping("/sales-by-day")
    @PreAuthorize("hasAuthority('report:read')")
    public List<DailySales> salesByDay(
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to) {
        Period period = period(from, to);
        return reportService.salesByDay(period.from(), period.to());
    }

    @GetMapping("/outlets")
    @PreAuthorize("hasAuthority('report:read')")
    public List<BranchPerformance> outlets(
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to) {
        Period period = period(from, to);
        return reportService.outletComparison(period.from(), period.to());
    }

    private record Period(Instant from, Instant to) {
    }

    /** The one place a report period is defaulted and validated. */
    private Period period(Instant from, Instant to) {
        ZoneId zone = properties.zone();
        LocalDate today = LocalDate.now(zone);
        Instant start = from != null ? from : today.atStartOfDay(zone).toInstant();
        Instant end = to != null ? to : today.plusDays(1).atStartOfDay(zone).toInstant();
        if (!start.isBefore(end)) {
            throw new BadRequestException("'from' must be before 'to'");
        }
        if (ChronoUnit.DAYS.between(start, end) > properties.maxRangeDays()) {
            throw new BadRequestException("Report range may not exceed " + properties.maxRangeDays() + " days");
        }
        return new Period(start, end);
    }
}
