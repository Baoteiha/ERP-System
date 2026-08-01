package vn.essvn.erpcafe.staff.web;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import vn.essvn.erpcafe.common.web.ApiVersions;
import vn.essvn.erpcafe.common.web.MoneyDto;
import vn.essvn.erpcafe.staff.application.EmployeeService;
import vn.essvn.erpcafe.staff.domain.Employee;
import vn.essvn.erpcafe.staff.web.StaffDtos.EmployeeRequest;
import vn.essvn.erpcafe.staff.web.StaffDtos.EmployeeResponse;

/**
 * Employee master data for the acting user's company. No delete: employees
 * with shift history are deactivated instead.
 */
@RestController
@RequestMapping(ApiVersions.V1 + "/staff/employees")
public class EmployeeController {

    private final EmployeeService employeeService;

    public EmployeeController(EmployeeService employeeService) {
        this.employeeService = employeeService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('staff:read')")
    public List<EmployeeResponse> list() {
        return employeeService.list().stream().map(EmployeeController::toResponse).toList();
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('staff:read')")
    public EmployeeResponse get(@PathVariable UUID id) {
        return toResponse(employeeService.get(id));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('staff:write')")
    public ResponseEntity<EmployeeResponse> create(@Valid @RequestBody EmployeeRequest request) {
        EmployeeResponse body = toResponse(employeeService.create(
                request.fullName(), request.position(), request.hourlyRate(),
                request.phone(), request.email()));
        return ResponseEntity.created(URI.create(ApiVersions.V1 + "/staff/employees/" + body.id())).body(body);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('staff:write')")
    public EmployeeResponse update(@PathVariable UUID id, @Valid @RequestBody EmployeeRequest request) {
        return toResponse(employeeService.update(id, request.fullName(), request.position(),
                request.hourlyRate(), request.phone(), request.email(),
                request.active() == null || request.active()));
    }

    static EmployeeResponse toResponse(Employee e) {
        return new EmployeeResponse(e.getId(), e.getFullName(), e.getPosition(),
                MoneyDto.from(e.getHourlyRate()), e.getPhone(), e.getEmail(), e.isActive());
    }
}
