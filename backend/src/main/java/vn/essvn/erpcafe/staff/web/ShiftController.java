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
import vn.essvn.erpcafe.common.web.ApiVersions;
import vn.essvn.erpcafe.common.web.MoneyDto;
import vn.essvn.erpcafe.staff.application.EmployeeService;
import vn.essvn.erpcafe.staff.application.ShiftService;
import vn.essvn.erpcafe.staff.domain.Employee;
import vn.essvn.erpcafe.staff.domain.Shift;
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

    public ShiftController(ShiftService shiftService, EmployeeService employeeService) {
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
        Map<UUID, String> names = employeeNames();
        return shiftService.list(start, end).stream().map(s -> toResponse(s, names)).toList();
    }

    @PostMapping
    @PreAuthorize("hasAuthority('staff:write')")
    public ResponseEntity<ShiftResponse> schedule(@Valid @RequestBody ShiftRequest request) {
        Shift shift = shiftService.schedule(request.employeeId(),
                request.scheduledStart(), request.scheduledEnd(), request.note());
        ShiftResponse body = toResponse(shift, employeeNames());
        return ResponseEntity.created(URI.create(ApiVersions.V1 + "/staff/shifts/" + body.id())).body(body);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('staff:write')")
    public ShiftResponse reschedule(@PathVariable UUID id, @Valid @RequestBody RescheduleRequest request) {
        return toResponse(shiftService.reschedule(id, request.scheduledStart(), request.scheduledEnd(),
                request.note()), employeeNames());
    }

    @PostMapping("/{id}/clock-in")
    @PreAuthorize("hasAuthority('staff:clock')")
    public ShiftResponse clockIn(@PathVariable UUID id) {
        return toResponse(shiftService.clockIn(id), employeeNames());
    }

    @PostMapping("/{id}/clock-out")
    @PreAuthorize("hasAuthority('staff:clock')")
    public ShiftResponse clockOut(@PathVariable UUID id) {
        return toResponse(shiftService.clockOut(id), employeeNames());
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAuthority('staff:write')")
    public ShiftResponse cancel(@PathVariable UUID id) {
        return toResponse(shiftService.cancel(id), employeeNames());
    }

    private Map<UUID, String> employeeNames() {
        return employeeService.list().stream()
                .collect(Collectors.toMap(Employee::getId, Employee::getFullName, (a, b) -> a));
    }

    private static ShiftResponse toResponse(Shift s, Map<UUID, String> names) {
        return new ShiftResponse(s.getId(), s.getBranchId(), s.getEmployeeId(),
                names.getOrDefault(s.getEmployeeId(), "—"),
                s.getStatus().name(), s.getScheduledStart(), s.getScheduledEnd(),
                s.getClockInAt(), s.getClockOutAt(),
                MoneyDto.from(s.getHourlyRate()), MoneyDto.from(s.getLaborCost()), s.getNote());
    }
}
