package vn.essvn.erpcafe.common.domain;

import java.util.UUID;

import org.hibernate.annotations.TenantId;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;
import vn.essvn.erpcafe.common.context.TenantContext;

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

    /**
     * Hibernate stamps the session's company only when the INSERT runs, at flush. Taking it at persist
     * makes it readable at once (an event published in the same transaction carries it); Hibernate
     * still refuses the insert if it is not the session's company. Outside a company (the system
     * scope, or no tenant at all) there is no company to take, so the row is refused.
     */
    @PrePersist
    void takeCompanyFromTenant() {
        if (companyId == null) {
            companyId = TenantContext.companyForNewRow();
        }
    }
}
