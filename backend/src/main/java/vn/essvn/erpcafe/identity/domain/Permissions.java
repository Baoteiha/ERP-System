package vn.essvn.erpcafe.identity.domain;

import java.util.List;
import java.util.Map;
import java.util.Set;

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
    public static final String STAFF_READ = "staff:read";
    public static final String STAFF_WRITE = "staff:write";
    public static final String STAFF_CLOCK = "staff:clock";
    public static final String REPORT_READ = "report:read";

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
            Map.entry(SALES_REFUND, "Void and refund orders"),
            Map.entry(STAFF_READ, "View employees and shifts"),
            Map.entry(STAFF_WRITE, "Manage employees and schedule shifts"),
            Map.entry(STAFF_CLOCK, "Clock shifts in and out"),
            Map.entry(REPORT_READ, "View reports and analytics"));

    public static final List<String> ALL = List.copyOf(CATALOG.keySet());

    /**
     * Company-level administration. Companies, users and roles belong to no one branch, so these
     * are weighed against every branch a user holds; every other permission is weighed at the
     * branch the request acts in (ADR-0007).
     */
    public static final Set<String> COMPANY_LEVEL =
            Set.of(COMPANY_READ, COMPANY_WRITE, USER_READ, USER_WRITE, ROLE_READ, ROLE_WRITE);

    /**
     * Raised by the release that adds permissions to the catalog. On startup each default role
     * is offered only the permissions {@link #SINCE} dates after the version it last synced to,
     * so a permission is granted once, when it is new, and an owner's later removal sticks.
     */
    public static final int CATALOG_VERSION = 1;

    /** name → the {@link #CATALOG_VERSION} that introduced it; a new permission takes the raised version. */
    public static final Map<String, Integer> SINCE = Map.ofEntries(
            Map.entry(COMPANY_READ, 1),
            Map.entry(COMPANY_WRITE, 1),
            Map.entry(BRANCH_READ, 1),
            Map.entry(BRANCH_WRITE, 1),
            Map.entry(USER_READ, 1),
            Map.entry(USER_WRITE, 1),
            Map.entry(ROLE_READ, 1),
            Map.entry(ROLE_WRITE, 1),
            Map.entry(CATALOG_READ, 1),
            Map.entry(CATALOG_WRITE, 1),
            Map.entry(INVENTORY_READ, 1),
            Map.entry(INVENTORY_WRITE, 1),
            Map.entry(PURCHASING_READ, 1),
            Map.entry(PURCHASING_WRITE, 1),
            Map.entry(SALES_READ, 1),
            Map.entry(SALES_WRITE, 1),
            Map.entry(SALES_REFUND, 1),
            Map.entry(STAFF_READ, 1),
            Map.entry(STAFF_WRITE, 1),
            Map.entry(STAFF_CLOCK, 1),
            Map.entry(REPORT_READ, 1));

    private Permissions() {
    }
}
