package vn.essvn.erpcafe.common.persistence;

import java.util.UUID;

import org.hibernate.context.spi.CurrentTenantIdentifierResolver;

import vn.essvn.erpcafe.common.context.TenantContext;

/**
 * Class: TenantIdentifierResolver
 * Purpose: Gives each Hibernate session the company from {@link TenantContext}, so tenant-owned rows are filtered and stamped.
 */
public class TenantIdentifierResolver implements CurrentTenantIdentifierResolver<UUID> {

    // No tenant resolves to an identifier that matches no company, so the session fails closed.
    @Override
    public UUID resolveCurrentTenantIdentifier() {
        if (TenantContext.isSystem()) {
            return TenantContext.SYSTEM_TENANT_ID;
        }
        return TenantContext.current().orElse(TenantContext.NO_TENANT_ID);
    }

    // Spring opens and binds sessions itself; Hibernate's current-session lookup is never used.
    @Override
    public boolean validateExistingCurrentSessions() {
        return false;
    }

    @Override
    public boolean isRoot(UUID tenantId) {
        return TenantContext.SYSTEM_TENANT_ID.equals(tenantId);
    }
}
