package vn.essvn.erpcafe.common.domain;

import java.util.UUID;

import org.hibernate.annotations.TenantId;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;

/**
 * Class: TenantOwnedEntity
 * Purpose: Base for rows a company owns, whose company is stamped from the session's tenant on insert and filters every read.
 */
@MappedSuperclass
public abstract class TenantOwnedEntity extends AuditableEntity {

    // No setter: a row is written inside its own company's tenant (TenantContext), never assigned one.
    @TenantId
    @Column(name = "company_id", nullable = false, updatable = false)
    private UUID companyId;

    public UUID getCompanyId() {
        return companyId;
    }
}
