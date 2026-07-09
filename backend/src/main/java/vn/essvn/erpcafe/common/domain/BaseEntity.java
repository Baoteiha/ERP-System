package vn.essvn.erpcafe.common.domain;

import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.Version;

/**
 * Root of the entity hierarchy: a UUID primary key plus an optimistic-lock
 * version. All persistent entities extend this (directly or via
 * {@link AuditableEntity} / {@link BranchScopedEntity}).
 */
@MappedSuperclass
public abstract class BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    // Optimistic-lock version: Hibernate adds "AND version = ?" to every UPDATE and bumps it,
    // so a concurrent edit to a stale copy fails (surfaced as HTTP 409) instead of silently
    // overwriting. Every entity inherits this — "safe by default" for a multi-user ERP.
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    public UUID getId() {
        return id;
    }

    public Long getVersion() {
        return version;
    }

    /**
     * Identity is the assigned UUID. The {@code id != null} check matters: two not-yet-persisted
     * entities (both null id) must not be considered equal, or they'd collide in a Set/Map.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof BaseEntity other)) {
            return false;
        }
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
