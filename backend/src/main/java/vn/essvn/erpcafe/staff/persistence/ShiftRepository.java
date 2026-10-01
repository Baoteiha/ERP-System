package vn.essvn.erpcafe.staff.persistence;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import vn.essvn.erpcafe.staff.domain.Shift;
import vn.essvn.erpcafe.staff.domain.ShiftStatus;

public interface ShiftRepository extends JpaRepository<Shift, UUID> {

    Optional<Shift> findByIdAndBranchId(UUID id, UUID branchId);

    List<Shift> findByBranchIdAndScheduledStartBetweenOrderByScheduledStart(
            UUID branchId, Instant from, Instant to);

    List<Shift> findByBranchIdAndStatusOrderByScheduledStart(UUID branchId, ShiftStatus status);

    boolean existsByCompanyIdAndBranchIdAndStatus(UUID companyId, UUID branchId, ShiftStatus status);

    boolean existsByEmployeeIdAndStatus(UUID employeeId, ShiftStatus status);

    List<Shift> findByEmployeeIdAndStatusAndScheduledStartAfter(UUID employeeId, ShiftStatus status, Instant after);

    /** Captured labor cost of shifts completed in the window, per branch; branches without any are absent. */
    @Query("""
            select s.branchId as branchId, sum(s.laborCost.amount) as total
            from Shift s
            where s.companyId = :companyId
              and s.branchId in :branchIds
              and s.status = vn.essvn.erpcafe.staff.domain.ShiftStatus.COMPLETED
              and s.clockOutAt >= :from and s.clockOutAt < :to
            group by s.branchId
            """)
    List<BranchLaborCost> laborCostByBranch(@Param("companyId") UUID companyId,
            @Param("branchIds") Collection<UUID> branchIds,
            @Param("from") Instant from, @Param("to") Instant to);

    interface BranchLaborCost {

        UUID getBranchId();

        BigDecimal getTotal();
    }
}
