package vn.essvn.erpcafe.staff.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.essvn.erpcafe.common.context.BranchContext;
import vn.essvn.erpcafe.common.events.DomainEventPublisher;
import vn.essvn.erpcafe.common.exception.BusinessRuleException;
import vn.essvn.erpcafe.common.exception.ForbiddenException;
import vn.essvn.erpcafe.common.exception.ResourceNotFoundException;
import vn.essvn.erpcafe.staff.api.ShiftCompleted;
import vn.essvn.erpcafe.staff.api.StaffApi;
import vn.essvn.erpcafe.staff.domain.Employee;
import vn.essvn.erpcafe.staff.domain.Shift;
import vn.essvn.erpcafe.staff.domain.ShiftStatus;
import vn.essvn.erpcafe.staff.persistence.EmployeeRepository;
import vn.essvn.erpcafe.staff.persistence.ShiftRepository;

/**
 * Shift scheduling and time-clock use cases for the active branch. Clock-in
 * snapshots the employee's current hourly rate; clock-out captures the shift's
 * labor cost. Implements {@link StaffApi}.
 */
@Service
@Transactional
@EnableConfigurationProperties(StaffProperties.class)
public class ShiftService implements StaffApi {

    private final ShiftRepository shiftRepository;
    private final EmployeeRepository employeeRepository;
    private final StaffProperties properties;
    // Lazy: the encoder lives in SecurityConfig, whose filter chain reaches back here via organization.
    private final ObjectProvider<PasswordEncoder> passwordEncoder;
    private final PinAttemptService pinAttempts;
    private final DomainEventPublisher events;

    public ShiftService(ShiftRepository shiftRepository, EmployeeRepository employeeRepository,
            StaffProperties properties, ObjectProvider<PasswordEncoder> passwordEncoder,
            PinAttemptService pinAttempts, DomainEventPublisher events) {
        this.events = events;
        this.pinAttempts = pinAttempts;
        this.passwordEncoder = passwordEncoder;
        this.properties = properties;
        this.shiftRepository = shiftRepository;
        this.employeeRepository = employeeRepository;
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
        Employee employee = employeeRepository.findById(employeeId)
                .orElseThrow(() -> ResourceNotFoundException.of("Employee", employeeId));
        if (!employee.isActive()) {
            throw new BusinessRuleException("Cannot schedule an inactive employee");
        }
        return shiftRepository.save(new Shift(BranchContext.require(), employeeId, start, end, note));
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
        publishCompleted(shift);
        return shift;
    }

    /** The shift's employee, once the PIN is proven — before any state rule can leak anything. */
    private Employee employeeOf(Shift shift, String pin) {
        Employee employee = employeeRepository.findById(shift.getEmployeeId())
                .orElseThrow(() -> ResourceNotFoundException.of("Employee", shift.getEmployeeId()));
        if (employee.hasPin()) {
            pinAttempts.checkNotLocked(employee.getId());
            if (!employee.pinMatches(pin, passwordEncoder.getObject())) {
                pinAttempts.recordFailure(employee.getId());
                throw new ForbiddenException("Wrong PIN");
            }
            pinAttempts.reset(employee.getId());
        }
        return employee;
    }

    /** Manager correction of a punch; see {@link Shift#adjust}. */
    public Shift adjust(UUID id, Instant clockIn, Instant clockOut) {
        Shift shift = get(id);
        shift.adjust(clockIn, clockOut, properties.maxAdjustedShiftLength(), Instant.now());
        publishCompleted(shift);
        return shift;
    }

    // Reports book a shift's labour on the day it ended; an adjustment restates both.
    private void publishCompleted(Shift shift) {
        events.publish(new ShiftCompleted(shift.getId(), shift.getCompanyId(), shift.getBranchId(),
                shift.getClockOutAt(), shift.getLaborCost().getAmount()));
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
}
