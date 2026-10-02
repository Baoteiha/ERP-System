package vn.essvn.erpcafe.reporting.persistence;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.TenantId;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Class: AppliedEvent
 * Purpose: Marks a domain event the report projection has already applied, so a second delivery changes nothing.
 */
@Entity
@Immutable
@Table(name = "report_applied_event")
public class AppliedEvent {

    @Id
    @Column(name = "event_id")
    private UUID eventId;

    @TenantId
    @Column(name = "company_id", nullable = false, updatable = false)
    private UUID companyId;

    @Column(name = "applied_at", nullable = false)
    private Instant appliedAt;

    protected AppliedEvent() {
    }

    public UUID getEventId() {
        return eventId;
    }

    public Instant getAppliedAt() {
        return appliedAt;
    }
}
