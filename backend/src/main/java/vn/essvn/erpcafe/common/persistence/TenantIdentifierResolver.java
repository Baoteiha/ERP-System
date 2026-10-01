package vn.essvn.erpcafe.common.persistence;

import java.util.UUID;

import org.hibernate.context.spi.CurrentTenantIdentifierResolver;

import vn.essvn.erpcafe.common.context.TenantContext;

/**
 * Class: TenantIdentifierResolver
 * Purpose: Gives each Hibernate session the company from {@link TenantContext}, so tenant-owned rows are filtered and stamped.
 */
public class TenantIdentifierResolver implements CurrentTenantIdentifierResolver<UUID> {

    /** Matches no company: a session opened outside any tenant sees no tenant-owned row (fails closed). */
    public static final UUID NO_TENANT = new UUID(0L, 0L);

    /** The system scope's identifier, for which Hibernate lifts the tenant filter. */
    public static final UUID SYSTEM = new UUID(-1L, -1L);

    @Override
    public UUID resolveCurrentTenantIdentifier() {
        if (TenantContext.isSystem()) {
            return SYSTEM;
        }
        return TenantContext.current().orElse(NO_TENANT);
    }

    // Spring opens and binds sessions itself; Hibernate's current-session lookup is never used.
    @Override
    public boolean validateExistingCurrentSessions() {
        return false;
    }

    @Override
    public boolean isRoot(UUID tenantId) {
        return SYSTEM.equals(tenantId);
    }
}
