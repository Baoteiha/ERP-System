package vn.essvn.erpcafe.staff.web;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import vn.essvn.erpcafe.common.web.MoneyDto;

/** Request/response records for the staff module's web layer. */
public final class StaffDtos {

    private StaffDtos() {
    }

    public record EmployeeRequest(
            @NotBlank @Size(max = 255) String fullName,
            @Size(max = 100) String position,
            @DecimalMin(value = "0", message = "Hourly rate cannot be negative") BigDecimal hourlyRate,
            @Size(max = 32) String phone,
            @Size(max = 255) String email,
            Boolean active) {
    }

    public record EmployeeResponse(UUID id, String fullName, String position,
            MoneyDto hourlyRate, String phone, String email, boolean active) {
    }

    /** The PIN is optional on a punch: employees who have none set clock without one. */
    public record ClockRequest(String pin) {
    }

    public record PinRequest(@NotNull @Pattern(regexp = "\\d{4,6}", message = "PIN must be 4 to 6 digits") String pin) {
    }

    public record ShiftRequest(
            @NotNull UUID employeeId,
            @NotNull Instant scheduledStart,
            @NotNull Instant scheduledEnd,
            @Size(max = 500) String note) {
    }

    public record RescheduleRequest(
            @NotNull Instant scheduledStart,
            @NotNull Instant scheduledEnd,
            @Size(max = 500) String note) {
    }

    /** Manager correction of a punch; the clock-in is optional and keeps its value when absent. */
    public record AdjustRequest(Instant clockIn, @NotNull Instant clockOut) {
    }

    public record ShiftResponse(UUID id, UUID branchId, UUID employeeId, String employeeName,
            String status, Instant scheduledStart, Instant scheduledEnd,
            Instant clockInAt, Instant clockOutAt,
            MoneyDto hourlyRate, MoneyDto laborCost, String note) {
    }
}
