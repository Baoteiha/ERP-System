package vn.essvn.erpcafe.staff.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.essvn.erpcafe.common.domain.Money;
import vn.essvn.erpcafe.common.exception.ResourceNotFoundException;
import vn.essvn.erpcafe.staff.domain.Employee;
import vn.essvn.erpcafe.staff.domain.Shift;
import vn.essvn.erpcafe.staff.domain.ShiftStatus;
import vn.essvn.erpcafe.staff.persistence.EmployeeRepository;
import vn.essvn.erpcafe.staff.persistence.ShiftRepository;

/**
 * Employee master data within the acting user's company. Employees are never
 * deleted — completed shifts reference them for labor history — only deactivated.
 */
@Service
@Transactional
public class EmployeeService {

    private final EmployeeRepository employeeRepository;
    private final ShiftRepository shiftRepository;
    // Lazy: the encoder lives in SecurityConfig, whose filter chain reaches back here via organization.
    private final ObjectProvider<PasswordEncoder> passwordEncoder;

    public EmployeeService(EmployeeRepository employeeRepository, ShiftRepository shiftRepository,
            ObjectProvider<PasswordEncoder> passwordEncoder) {
        this.passwordEncoder = passwordEncoder;
        this.employeeRepository = employeeRepository;
        this.shiftRepository = shiftRepository;
    }

    @Transactional(readOnly = true)
    public List<Employee> list() {
        return employeeRepository.findAllByOrderByFullName();
    }

    /** Names of just these employees, so a shift list does not load the whole company roster. */
    @Transactional(readOnly = true)
    public Map<UUID, String> namesByIds(Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return employeeRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(Employee::getId, Employee::getFullName));
    }

    /** Which of these employees have a PIN, so a shift list can tell the UI when to ask for one. */
    @Transactional(readOnly = true)
    public Set<UUID> idsWithPin(Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return Set.of();
        }
        return employeeRepository.findAllById(ids).stream()
                .filter(Employee::hasPin).map(Employee::getId).collect(Collectors.toSet());
    }

    @Transactional(readOnly = true)
    public Employee get(UUID id) {
        return employeeRepository.findById(id)
                .orElseThrow(() -> ResourceNotFoundException.of("Employee", id));
    }

    public Employee create(String fullName, String position, BigDecimal hourlyRate,
            String phone, String email) {
        Employee employee = new Employee(fullName, position, rate(hourlyRate));
        employee.setPhone(phone);
        employee.setEmail(email);
        return employeeRepository.save(employee);
    }

    public Employee update(UUID id, String fullName, String position, BigDecimal hourlyRate,
            String phone, String email, Boolean active) {
        Employee employee = get(id);
        employee.setFullName(fullName);
        employee.setPosition(position);
        employee.setHourlyRate(rate(hourlyRate));
        employee.setPhone(phone);
        employee.setEmail(email);
        if (active != null) {
            if (employee.isActive() && !active) {
                cancelFutureShifts(employee.getId());
            }
            employee.setActive(active);
        }
        return employee;
    }

    public void setPin(UUID id, String rawPin) {
        get(id).setPin(rawPin, passwordEncoder.getObject());
    }

    /** A deactivated employee must not stay on the rota; past and open shifts are history and stay. */
    private void cancelFutureShifts(UUID employeeId) {
        shiftRepository.findByEmployeeIdAndStatusAndScheduledStartAfter(
                employeeId, ShiftStatus.SCHEDULED, Instant.now()).forEach(Shift::cancel);
    }

    private static Money rate(BigDecimal hourlyRate) {
        return Money.of(hourlyRate == null ? BigDecimal.ZERO : hourlyRate);
    }
}
