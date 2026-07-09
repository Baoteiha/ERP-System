package vn.essvn.erpcafe.identity.domain;

import java.util.List;
import java.util.Map;

/**
 * The permission catalog — the single source of truth for authority strings.
 * Synced into the {@code permission} table on startup. New permissions are
 * added here as later modules introduce capabilities.
 */
public final class Permissions {

    public static final String COMPANY_READ = "company:read";
    public static final String COMPANY_WRITE = "company:write";
    public static final String BRANCH_READ = "branch:read";
    public static final String BRANCH_WRITE = "branch:write";
    public static final String USER_READ = "user:read";
    public static final String USER_WRITE = "user:write";
    public static final String ROLE_READ = "role:read";
    public static final String ROLE_WRITE = "role:write";
    public static final String CATALOG_READ = "catalog:read";
    public static final String CATALOG_WRITE = "catalog:write";
    public static final String INVENTORY_READ = "inventory:read";
    public static final String INVENTORY_WRITE = "inventory:write";
    public static final String PURCHASING_READ = "purchasing:read";
    public static final String PURCHASING_WRITE = "purchasing:write";
    public static final String SALES_READ = "sales:read";
    public static final String SALES_WRITE = "sales:write";
    public static final String SALES_REFUND = "sales:refund";

    /** name → human description; iterated when syncing the permission table. */
    public static final Map<String, String> CATALOG = Map.ofEntries(
            Map.entry(COMPANY_READ, "View companies"),
            Map.entry(COMPANY_WRITE, "Create/update companies"),
            Map.entry(BRANCH_READ, "View branches"),
            Map.entry(BRANCH_WRITE, "Create/update branches"),
            Map.entry(USER_READ, "View users"),
            Map.entry(USER_WRITE, "Create/update users and grant access"),
            Map.entry(ROLE_READ, "View roles"),
            Map.entry(ROLE_WRITE, "Create/update roles"),
            Map.entry(CATALOG_READ, "View menu, products, recipes, modifiers"),
            Map.entry(CATALOG_WRITE, "Create/update menu, products, recipes, modifiers"),
            Map.entry(INVENTORY_READ, "View ingredients, suppliers, stock, movements"),
            Map.entry(INVENTORY_WRITE, "Create/update ingredients, suppliers, stock adjustments"),
            Map.entry(PURCHASING_READ, "View purchase orders"),
            Map.entry(PURCHASING_WRITE, "Create/manage purchase orders and receiving"),
            Map.entry(SALES_READ, "View orders"),
            Map.entry(SALES_WRITE, "Create orders, take payments, complete"),
            Map.entry(SALES_REFUND, "Void and refund orders"));

    public static final List<String> ALL = List.copyOf(CATALOG.keySet());

    private Permissions() {
    }
}
