package vn.essvn.erpcafe.reporting.persistence;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.LockModeType;

@Transactional(readOnly = true)
public interface ShiftLaborRepository extends JpaRepository<ShiftLabor, UUID> {

    /** The shift's current share, locked until the restating transaction ends. */
    @Transactional(propagation = Propagation.MANDATORY)
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from ShiftLabor s where s.shiftId = :shiftId")
    Optional<ShiftLabor> lockByShiftId(@Param("shiftId") UUID shiftId);
}
