package vn.essvn.erpcafe.staff.web;

import java.net.URI;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import vn.essvn.erpcafe.common.exception.BadRequestException;
import vn.essvn.erpcafe.common.web.ApiVersions;
import vn.essvn.erpcafe.common.web.MoneyDto;
import vn.essvn.erpcafe.identity.security.CurrentUser;
import vn.essvn.erpcafe.staff.application.EmployeeService;
import vn.essvn.erpcafe.staff.application.ShiftService;
import vn.essvn.erpcafe.staff.domain.Shift;
import vn.essvn.erpcafe.staff.web.StaffDtos.AdjustRequest;
import vn.essvn.erpcafe.staff.web.StaffDtos.ClockRequest;
import vn.essvn.erpcafe.staff.web.StaffDtos.RescheduleRequest;
import vn.essvn.erpcafe.staff.web.StaffDtos.ShiftRequest;
import vn.essvn.erpcafe.staff.web.StaffDtos.ShiftResponse;

/**
 * Shift scheduling and the time clock for the active branch (requires
 * {@code X-Branch-Id}). Clocking is a separate permission so cashiers can
 * punch in/out without being able to edit the schedule.
 */
@RestController
@RequestMapping(ApiVersions.V1 + "/staff/shifts")
public class ShiftController {

    private final ShiftService shiftService;
    private final EmployeeService employeeService;
    private final CurrentUser currentUser;

    public ShiftController(ShiftService shiftService, EmployeeService employeeService,
            CurrentUser currentUser) {
        this.currentUser = currentUser;
        this.shiftService = shiftService;
        this.employeeService = employeeService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('staff:read')")
    public List<ShiftResponse> list(
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to) {
        Instant start = from != null ? from : Instant.now().truncatedTo(ChronoUnit.DAYS).minus(1, ChronoUnit.DAYS);
        Instant end = to != null ? to : start.plus(8, ChronoUnit.DAYS);
        if (!start.isBefore(end)) {
            throw new BadRequestException("'from' must be before 'to'");
        }
        return responses(shiftService.list(start, end));
    }

    /** Shifts running right now at the branch, whenever they were scheduled. */
    @GetMapping("/open")
    @PreAuthorize("hasAuthority('staff:read')")
    public List<ShiftResponse> open() {
        return responses(shiftService.listOpen());
    }

    @PostMapping
    @PreAuthorize("hasAuthority('staff:write')")
    public ResponseEntity<ShiftResponse> schedule(@Valid @RequestBody ShiftRequest request) {
        Shift shift = shiftService.schedule(request.employeeId(),
                request.scheduledStart(), request.scheduledEnd(), request.note());
        ShiftResponse body = toResponse(shift, employeeNames(shift));
        return ResponseEntity.created(URI.create(ApiVersions.V1 + "/staff/shifts/" + body.id())).body(body);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('staff:write')")
    public ShiftResponse reschedule(@PathVariable UUID id, @Valid @RequestBody RescheduleRequest request) {
        Shift shift = shiftService.reschedule(id, request.scheduledStart(), request.scheduledEnd(),
                request.note());
        return toResponse(shift, employeeNames(shift));
    }

    @PostMapping("/{id}/clock-in")
    @PreAuthorize("hasAuthority('staff:clock')")
    public ShiftResponse clockIn(@PathVariable UUID id, @RequestBody(required = false) ClockRequest request) {
        Shift shift = shiftService.clockIn(id, pinOf(request));
        return toResponse(shift, employeeNames(shift));
    }

    @PostMapping("/{id}/clock-out")
    @PreAuthorize("hasAuthority('staff:clock')")
    public ShiftResponse clockOut(@PathVariable UUID id, @RequestBody(required = false) ClockRequest request) {
        Shift shift = shiftService.clockOut(id, pinOf(request));
        return toResponse(shift, employeeNames(shift));
    }

    @PostMapping("/{id}/adjust")
    @PreAuthorize("hasAuthority('staff:write')")
    public ShiftResponse adjust(@PathVariable UUID id, @Valid @RequestBody AdjustRequest request) {
        Shift shift = shiftService.adjust(id, request.clockIn(), request.clockOut());
        return toResponse(shift, employeeNames(shift));
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAuthority('staff:write')")
    public ShiftResponse cancel(@PathVariable UUID id) {
        Shift shift = shiftService.cancel(id);
        return toResponse(shift, employeeNames(shift));
    }

    private static String pinOf(ClockRequest request) {
        return request == null ? null : request.pin();
    }

    private List<ShiftResponse> responses(List<Shift> shifts) {
        Map<UUID, String> names = employeeService.namesByIds(
                shifts.stream().map(Shift::getEmployeeId).collect(Collectors.toSet()));
        return shifts.stream().map(s -> toResponse(s, names)).toList();
    }

    private Map<UUID, String> employeeNames(Shift shift) {
        return employeeService.namesByIds(List.of(shift.getEmployeeId()));
    }

    private ShiftResponse toResponse(Shift s, Map<UUID, String> names) {
        boolean canSeePay = StaffAccess.canSeePay(currentUser);
        return new ShiftResponse(s.getId(), s.getBranchId(), s.getEmployeeId(),
                names.getOrDefault(s.getEmployeeId(), "—"),
                s.getStatus().name(), s.getScheduledStart(), s.getScheduledEnd(),
                s.getClockInAt(), s.getClockOutAt(),
                canSeePay ? MoneyDto.from(s.getHourlyRate()) : null,
                canSeePay ? MoneyDto.from(s.getLaborCost()) : null, s.getNote());
    }
}
