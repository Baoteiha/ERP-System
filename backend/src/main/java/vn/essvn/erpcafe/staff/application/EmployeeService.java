package vn.essvn.erpcafe.staff.application;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.essvn.erpcafe.common.domain.Money;
import vn.essvn.erpcafe.common.exception.ConflictException;
import vn.essvn.erpcafe.common.exception.ResourceNotFoundException;
import vn.essvn.erpcafe.identity.security.CurrentUser;
import vn.essvn.erpcafe.staff.domain.Employee;
import vn.essvn.erpcafe.staff.persistence.EmployeeRepository;

/**
 * Employee master data within the acting user's company. Employees are never
 * deleted — completed shifts reference them for labor history — only deactivated.
 */
@Service
@Transactional
public class EmployeeService {

    private final EmployeeRepository employeeRepository;
    private final CurrentUser currentUser;

    public EmployeeService(EmployeeRepository employeeRepository, CurrentUser currentUser) {
        this.employeeRepository = employeeRepository;
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
        if (employeeRepository.existsByCompanyIdAndFullName(companyId, fullName)) {
            throw new ConflictException("Employee already exists: " + fullName);
        }
        Employee employee = new Employee(companyId, fullName, position, rate(hourlyRate));
        employee.setPhone(phone);
        employee.setEmail(email);
        return employeeRepository.save(employee);
    }

    public Employee update(UUID id, String fullName, String position, BigDecimal hourlyRate,
            String phone, String email, boolean active) {
        Employee employee = get(id);
        if (!employee.getFullName().equals(fullName)
                && employeeRepository.existsByCompanyIdAndFullName(employee.getCompanyId(), fullName)) {
            throw new ConflictException("Employee already exists: " + fullName);
        }
        employee.setFullName(fullName);
        employee.setPosition(position);
        employee.setHourlyRate(rate(hourlyRate));
        employee.setPhone(phone);
        employee.setEmail(email);
        employee.setActive(active);
        return employee;
    }

    private static Money rate(BigDecimal hourlyRate) {
        return Money.of(hourlyRate == null ? BigDecimal.ZERO : hourlyRate);
    }
}
