package vn.essvn.erpcafe.identity.domain;

import java.util.List;
import java.util.Set;

/**
 * Default roles seeded for a new company, each with its permission set. OWNER
 * gets everything; MANAGER and CASHIER get progressively narrower subsets.
 */
public enum DefaultRole {

    OWNER("Full access", Set.copyOf(Permissions.ALL)),

    MANAGER("Branch management", Set.of(
            Permissions.COMPANY_READ,
            Permissions.BRANCH_READ,
            Permissions.USER_READ,
            Permissions.USER_WRITE,
            Permissions.ROLE_READ,
            Permissions.CATALOG_READ,
            Permissions.CATALOG_WRITE,
            Permissions.INVENTORY_READ,
            Permissions.INVENTORY_WRITE,
            Permissions.PURCHASING_READ,
            Permissions.PURCHASING_WRITE,
            Permissions.SALES_READ,
            Permissions.SALES_WRITE,
            Permissions.SALES_REFUND,
            Permissions.STAFF_READ,
            Permissions.STAFF_WRITE,
            Permissions.STAFF_CLOCK,
            Permissions.REPORT_READ)),

    CASHIER("Point of sale", Set.of(
            Permissions.BRANCH_READ,
            Permissions.CATALOG_READ,
            Permissions.INVENTORY_READ,
            Permissions.SALES_READ,
            Permissions.SALES_WRITE, // cashier can sell but NOT refund/void
            Permissions.STAFF_READ,  // sees the schedule…
            Permissions.STAFF_CLOCK)); // …and can punch in/out, but cannot edit it

    private final String description;
    private final Set<String> permissions;

    DefaultRole(String description, Set<String> permissions) {
        this.description = description;
        this.permissions = permissions;
    }

    public String roleName() {
        return name();
    }

    public String description() {
        return description;
    }

    public Set<String> permissions() {
        return permissions;
    }

    public static List<DefaultRole> all() {
        return List.of(values());
    }
}
