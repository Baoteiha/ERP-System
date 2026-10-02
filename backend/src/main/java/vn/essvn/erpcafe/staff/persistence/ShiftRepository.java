package vn.essvn.erpcafe.staff.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import vn.essvn.erpcafe.staff.domain.Shift;
import vn.essvn.erpcafe.staff.domain.ShiftStatus;

@Transactional(readOnly = true)
public interface ShiftRepository extends JpaRepository<Shift, UUID> {

    Optional<Shift> findByIdAndBranchId(UUID id, UUID branchId);

    List<Shift> findByBranchIdAndScheduledStartBetweenOrderByScheduledStart(
            UUID branchId, Instant from, Instant to);

    List<Shift> findByBranchIdAndStatusOrderByScheduledStart(UUID branchId, ShiftStatus status);

    boolean existsByCompanyIdAndBranchIdAndStatus(UUID companyId, UUID branchId, ShiftStatus status);

    boolean existsByEmployeeIdAndStatus(UUID employeeId, ShiftStatus status);

    List<Shift> findByEmployeeIdAndStatusAndScheduledStartAfter(UUID employeeId, ShiftStatus status, Instant after);
}
