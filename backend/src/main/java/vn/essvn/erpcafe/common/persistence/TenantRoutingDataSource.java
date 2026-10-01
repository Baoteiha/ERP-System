package vn.essvn.erpcafe.common.persistence;

import java.util.Map;

import javax.sql.DataSource;

import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;

import vn.essvn.erpcafe.common.context.TenantContext;

/**
 * Class: TenantRoutingDataSource
 * Purpose: Connects the system scope as the schema owner and all other work as the app role row-level security binds.
 */
public class TenantRoutingDataSource extends AbstractRoutingDataSource {

    private enum Pool { APP, OWNER }

    public TenantRoutingDataSource(DataSource app, DataSource owner) {
        setTargetDataSources(Map.of(Pool.APP, app, Pool.OWNER, owner));
        setLenientFallback(false);
    }

    // Decided when a connection is borrowed, which a transaction does as it begins.
    @Override
    protected Object determineCurrentLookupKey() {
        return TenantContext.isSystem() ? Pool.OWNER : Pool.APP;
    }
}
