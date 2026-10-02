package vn.essvn.erpcafe.reporting.persistence;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Transactional(readOnly = true)
public interface AppliedEventRepository extends JpaRepository<AppliedEvent, UUID> {

    /**
     * Records that the projection applies this event; 0 when it already has. A second delivery
     * running at the same time waits on the key until the first commits, then gets 0.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    @Modifying
    @Query(value = "INSERT INTO report_applied_event (event_id, company_id) VALUES (:eventId, :companyId)"
            + " ON CONFLICT (event_id) DO NOTHING", nativeQuery = true)
    int claim(@Param("eventId") UUID eventId, @Param("companyId") UUID companyId);
}
