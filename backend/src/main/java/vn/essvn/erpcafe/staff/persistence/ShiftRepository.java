package vn.essvn.erpcafe.staff.persistence;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import vn.essvn.erpcafe.staff.domain.Shift;
import vn.essvn.erpcafe.staff.domain.ShiftStatus;

public interface ShiftRepository extends JpaRepository<Shift, UUID> {

    List<Shift> findByBranchIdAndScheduledStartBetweenOrderByScheduledStart(
            UUID branchId, Instant from, Instant to);

    List<Shift> findByBranchIdAndStatusOrderByScheduledStart(UUID branchId, ShiftStatus status);

    /** Total captured labor cost of shifts completed in the window. */
    @Query("""
            select coalesce(sum(s.laborCost.amount), 0)
            from Shift s
            where s.branchId = :branchId
              and s.status = vn.essvn.erpcafe.staff.domain.ShiftStatus.COMPLETED
              and s.clockOutAt >= :from and s.clockOutAt < :to
            """)
    BigDecimal laborCostBetween(@Param("branchId") UUID branchId,
            @Param("from") Instant from, @Param("to") Instant to);
}
