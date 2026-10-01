package vn.essvn.erpcafe.staff.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.essvn.erpcafe.common.domain.Money;
import vn.essvn.erpcafe.common.exception.ResourceNotFoundException;
import vn.essvn.erpcafe.identity.security.CurrentUser;
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
    private final CurrentUser currentUser;

    public EmployeeService(EmployeeRepository employeeRepository, ShiftRepository shiftRepository,
            CurrentUser currentUser) {
        this.employeeRepository = employeeRepository;
        this.shiftRepository = shiftRepository;
        this.currentUser = currentUser;
    }

    @Transactional(readOnly = true)
    public List<Employee> list() {
        return employeeRepository.findByCompanyIdOrderByFullName(currentUser.require().companyId());
    }

    @Transactional(readOnly = true)
    public Employee get(UUID id) {
        return employeeRepository.findByIdAndCompanyId(id, currentUser.require().companyId())
                .orElseThrow(() -> ResourceNotFoundException.of("Employee", id));
    }

    public Employee create(String fullName, String position, BigDecimal hourlyRate,
            String phone, String email) {
        UUID companyId = currentUser.require().companyId();
        Employee employee = new Employee(companyId, fullName, position, rate(hourlyRate));
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

    /** A deactivated employee must not stay on the rota; past and open shifts are history and stay. */
    private void cancelFutureShifts(UUID employeeId) {
        shiftRepository.findByEmployeeIdAndStatusAndScheduledStartAfter(
                employeeId, ShiftStatus.SCHEDULED, Instant.now()).forEach(Shift::cancel);
    }

    private static Money rate(BigDecimal hourlyRate) {
        return Money.of(hourlyRate == null ? BigDecimal.ZERO : hourlyRate);
    }
}
