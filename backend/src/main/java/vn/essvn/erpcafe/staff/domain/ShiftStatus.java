package vn.essvn.erpcafe.staff.domain;

/** Shift lifecycle: SCHEDULED → IN_PROGRESS → COMPLETED, or SCHEDULED → CANCELLED. */
public enum ShiftStatus {
    SCHEDULED,
    IN_PROGRESS,
    COMPLETED,
    CANCELLED
}
