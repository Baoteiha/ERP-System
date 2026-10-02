package vn.essvn.erpcafe.staff.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Class: ShiftCompleted
 * Purpose: States a completed shift's clock-out and labour cost, each time a clock-out or an adjustment sets them.
 */
public record ShiftCompleted(UUID shiftId, UUID companyId, UUID branchId, Instant clockOut, BigDecimal laborCost) {
}
