package vn.essvn.erpcafe.staff.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import vn.essvn.erpcafe.common.domain.BranchScopedEntity;
import vn.essvn.erpcafe.common.domain.Money;
import vn.essvn.erpcafe.common.exception.BusinessRuleException;

/**
 * A scheduled work shift for one employee at one branch. The entity enforces
 * the state machine; labor cost is captured at clock-out as
 * {@code worked hours × hourly rate snapshot} so history never drifts when an
 * employee's rate later changes.
 */
@Entity
@Table(name = "shift")
public class Shift extends BranchScopedEntity {

    @Column(name = "employee_id", nullable = false)
    private UUID employeeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private ShiftStatus status = ShiftStatus.SCHEDULED;

    @Column(name = "scheduled_start", nullable = false)
    private Instant scheduledStart;

    @Column(name = "scheduled_end", nullable = false)
    private Instant scheduledEnd;

    @Column(name = "clock_in_at")
    private Instant clockInAt;

    @Column(name = "clock_out_at")
    private Instant clockOutAt;

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "amount", column = @Column(name = "hourly_rate", precision = 19, scale = 4)),
            @AttributeOverride(name = "currency", column = @Column(name = "hourly_rate_currency", length = 3)),
    })
    private Money hourlyRate;

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "amount", column = @Column(name = "labor_cost", precision = 19, scale = 4)),
            @AttributeOverride(name = "currency", column = @Column(name = "labor_cost_currency", length = 3)),
    })
    private Money laborCost;

    @Column(name = "note", length = 500)
    private String note;

    protected Shift() {
    }

    public Shift(UUID branchId, UUID employeeId, Instant scheduledStart, Instant scheduledEnd, String note) {
        if (scheduledStart == null || scheduledEnd == null || !scheduledEnd.isAfter(scheduledStart)) {
            throw new BusinessRuleException("Shift end must be after its start");
        }
        setBranchId(branchId);
        this.employeeId = employeeId;
        this.scheduledStart = scheduledStart;
        this.scheduledEnd = scheduledEnd;
        this.note = note;
    }

    // --- state transitions ---

    public void reschedule(Instant start, Instant end, String note) {
        requireStatus(ShiftStatus.SCHEDULED, "reschedule");
        if (start == null || end == null || !end.isAfter(start)) {
            throw new BusinessRuleException("Shift end must be after its start");
        }
        this.scheduledStart = start;
        this.scheduledEnd = end;
        this.note = note;
    }

    /** Starts the shift, snapshotting the employee's current hourly rate. */
    public void clockIn(Instant at, Money currentHourlyRate) {
        requireStatus(ShiftStatus.SCHEDULED, "clock in");
        this.clockInAt = at;
        this.hourlyRate = currentHourlyRate == null ? Money.zero() : currentHourlyRate;
        this.status = ShiftStatus.IN_PROGRESS;
    }

    /** Ends the shift and captures its labor cost from the snapshotted rate. */
    public void clockOut(Instant at) {
        requireStatus(ShiftStatus.IN_PROGRESS, "clock out");
        if (!at.isAfter(clockInAt)) {
            throw new BusinessRuleException("Clock-out must be after clock-in");
        }
        this.clockOutAt = at;
        BigDecimal hours = BigDecimal.valueOf(Duration.between(clockInAt, at).toSeconds())
                .divide(BigDecimal.valueOf(3600), 6, RoundingMode.HALF_EVEN);
        this.laborCost = hourlyRate.multiply(hours);
        this.status = ShiftStatus.COMPLETED;
    }

    public void cancel() {
        requireStatus(ShiftStatus.SCHEDULED, "cancel");
        this.status = ShiftStatus.CANCELLED;
    }

    private void requireStatus(ShiftStatus expected, String action) {
        if (status != expected) {
            throw new BusinessRuleException("Shift must be %s to %s (was %s)".formatted(expected, action, status));
        }
    }

    // --- getters ---

    public UUID getEmployeeId() {
        return employeeId;
    }

    public ShiftStatus getStatus() {
        return status;
    }

    public Instant getScheduledStart() {
        return scheduledStart;
    }

    public Instant getScheduledEnd() {
        return scheduledEnd;
    }

    public Instant getClockInAt() {
        return clockInAt;
    }

    public Instant getClockOutAt() {
        return clockOutAt;
    }

    public Money getHourlyRate() {
        return hourlyRate;
    }

    public Money getLaborCost() {
        return laborCost;
    }

    public String getNote() {
        return note;
    }
}
