package vn.essvn.erpcafe.staff.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.essvn.erpcafe.common.context.BranchContext;
import vn.essvn.erpcafe.common.exception.BusinessRuleException;
import vn.essvn.erpcafe.common.exception.ForbiddenException;
import vn.essvn.erpcafe.common.exception.ResourceNotFoundException;
import vn.essvn.erpcafe.identity.security.CurrentUser;
import vn.essvn.erpcafe.organization.api.OrganizationApi;
import vn.essvn.erpcafe.staff.api.StaffApi;
import vn.essvn.erpcafe.staff.domain.Employee;
import vn.essvn.erpcafe.staff.domain.Shift;
import vn.essvn.erpcafe.staff.domain.ShiftStatus;
import vn.essvn.erpcafe.staff.persistence.EmployeeRepository;
import vn.essvn.erpcafe.staff.persistence.ShiftRepository;

/**
 * Shift scheduling and time-clock use cases for the active branch. Clock-in
 * snapshots the employee's current hourly rate; clock-out captures the shift's
 * labor cost. Implements {@link StaffApi} for reporting.
 */
@Service
@Transactional
@EnableConfigurationProperties(StaffProperties.class)
public class ShiftService implements StaffApi {

    private final ShiftRepository shiftRepository;
    private final EmployeeRepository employeeRepository;
    private final CurrentUser currentUser;
    private final StaffProperties properties;
    // Lazy: the encoder lives in SecurityConfig, whose filter chain reaches back here via organization.
    private final ObjectProvider<PasswordEncoder> passwordEncoder;
    // Lazy: organization already depends on this module (BranchService calls hasOpenShifts).
    private final ObjectProvider<OrganizationApi> organizationApi;

    public ShiftService(ShiftRepository shiftRepository, EmployeeRepository employeeRepository,
            CurrentUser currentUser, StaffProperties properties, ObjectProvider<PasswordEncoder> passwordEncoder,
            ObjectProvider<OrganizationApi> organizationApi) {
        this.passwordEncoder = passwordEncoder;
        this.organizationApi = organizationApi;
        this.properties = properties;
        this.shiftRepository = shiftRepository;
        this.employeeRepository = employeeRepository;
        this.currentUser = currentUser;
    }

    @Transactional(readOnly = true)
    public List<Shift> list(Instant from, Instant to) {
        return shiftRepository.findByBranchIdAndScheduledStartBetweenOrderByScheduledStart(
                BranchContext.require(), from, to);
    }

    @Transactional(readOnly = true)
    public Shift get(UUID id) {
        // Scope to the active branch: a bare findById would let a user reschedule, clock,
        // or cancel a shift in another branch/company by id (IDOR). Every caller runs with
        // an accessible branch (the JWT filter validates X-Branch-Id against the grant set).
        return shiftRepository.findByIdAndBranchId(id, BranchContext.require())
                .orElseThrow(() -> ResourceNotFoundException.of("Shift", id));
    }

    public Shift schedule(UUID employeeId, Instant start, Instant end, String note) {
        // The employee must belong to the caller's company — otherwise a shift (and its
        // labor cost) could be booked against another tenant's employee.
        Employee employee = employeeRepository.findByIdAndCompanyId(employeeId, currentUser.require().companyId())
                .orElseThrow(() -> ResourceNotFoundException.of("Employee", employeeId));
        if (!employee.isActive()) {
            throw new BusinessRuleException("Cannot schedule an inactive employee");
        }
        return shiftRepository.save(new Shift(employee.getCompanyId(), BranchContext.require(), employeeId, start, end, note));
    }

    public Shift reschedule(UUID id, Instant start, Instant end, String note) {
        Shift shift = get(id);
        shift.reschedule(start, end, note);
        return shift;
    }

    public Shift clockIn(UUID id, String pin) {
        Shift shift = get(id);
        Employee employee = employeeOf(shift, pin);
        if (!employee.isActive()) {
            throw new BusinessRuleException("Cannot clock in a deactivated employee");
        }
        // Friendly 422 for the usual case; uk_shift_one_open_per_employee is the real guard when
        // two clock-ins race (that one surfaces as 409 via DataIntegrityViolationException).
        if (shiftRepository.existsByEmployeeIdAndStatus(employee.getId(), ShiftStatus.IN_PROGRESS)) {
            throw new BusinessRuleException("Employee already has an open shift; clock it out first");
        }
        shift.clockIn(Instant.now(), employee.getHourlyRate());
        return shift;
    }

    public Shift clockOut(UUID id, String pin) {
        Shift shift = get(id);
        employeeOf(shift, pin);
        shift.clockOut(Instant.now(), properties.maxShiftLength());
        return shift;
    }

    /** The shift's employee, once the PIN is proven — before any state rule can leak anything. */
    private Employee employeeOf(Shift shift, String pin) {
        Employee employee = employeeRepository.findByIdAndCompanyId(shift.getEmployeeId(), shift.getCompanyId())
                .orElseThrow(() -> ResourceNotFoundException.of("Employee", shift.getEmployeeId()));
        if (!employee.pinMatches(pin, passwordEncoder.getObject())) {
            throw new ForbiddenException("Wrong PIN");
        }
        return employee;
    }

    /** Manager correction of a punch; see {@link Shift#adjust}. */
    public Shift adjust(UUID id, Instant clockIn, Instant clockOut) {
        Shift shift = get(id);
        shift.adjust(clockIn, clockOut, properties.maxAdjustedShiftLength(), Instant.now());
        return shift;
    }

    @Transactional(readOnly = true)
    public List<Shift> listOpen() {
        return shiftRepository.findByBranchIdAndStatusOrderByScheduledStart(
                BranchContext.require(), ShiftStatus.IN_PROGRESS);
    }

    public Shift cancel(UUID id) {
        Shift shift = get(id);
        shift.cancel();
        return shift;
    }

    // --- StaffApi ---

    @Override
    @Transactional(readOnly = true)
    public boolean hasOpenShifts(UUID companyId, UUID branchId) {
        return shiftRepository.existsByCompanyIdAndBranchIdAndStatus(companyId, branchId, ShiftStatus.IN_PROGRESS);
    }

    @Override
    @Transactional(readOnly = true)
    public BigDecimal laborCost(UUID companyId, UUID branchId, Instant from, Instant to) {
        return laborCostByBranch(companyId, List.of(branchId), from, to).get(branchId);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, BigDecimal> laborCostByBranch(UUID companyId, Collection<UUID> branchIds,
            Instant from, Instant to) {
        OrganizationApi organization = organizationApi.getObject();
        Map<UUID, BigDecimal> result = new HashMap<>();
        for (UUID branchId : branchIds) {
            organization.findBranch(companyId, branchId)
                    .orElseThrow(() -> ResourceNotFoundException.of("Branch", branchId));
            result.put(branchId, BigDecimal.ZERO);
        }
        if (!result.isEmpty()) {
            shiftRepository.laborCostByBranch(companyId, result.keySet(), from, to)
                    .forEach(row -> result.put(row.getBranchId(), row.getTotal()));
        }
        return result;
    }
}
