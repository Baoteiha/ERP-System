package vn.essvn.erpcafe.identity.domain;

import java.util.HashSet;
import java.util.Set;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import vn.essvn.erpcafe.common.domain.TenantOwnedEntity;

/**
 * A named bundle of {@link Permission}s (e.g. OWNER, MANAGER, CASHIER),
 * scoped to a company. Users are granted a role per branch via
 * {@link UserBranchAccess}.
 */
@Entity
@Table(name = "role", uniqueConstraints = @UniqueConstraint(name = "uk_role_company_name", columnNames = {"company_id", "name"}))
public class Role extends TenantOwnedEntity {

    @Column(name = "name", nullable = false, length = 64)
    private String name;

    @Column(name = "description")
    private String description;

    @ManyToMany(fetch = FetchType.EAGER, cascade = {CascadeType.PERSIST, CascadeType.MERGE})
    @JoinTable(name = "role_permission",
            joinColumns = @JoinColumn(name = "role_id"),
            inverseJoinColumns = @JoinColumn(name = "permission_id"))
    private Set<Permission> permissions = new HashSet<>();

    /** The {@link Permissions#CATALOG_VERSION} this role's default permissions were last synced to. */
    @Column(name = "synced_catalog_version", nullable = false)
    private int syncedCatalogVersion;

    protected Role() {
        // for JPA
    }

    public Role(String name, String description) {
        this.name = name;
        this.description = description;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Set<Permission> getPermissions() {
        return permissions;
    }

    public void setPermissions(Set<Permission> permissions) {
        this.permissions = permissions;
    }

    public int getSyncedCatalogVersion() {
        return syncedCatalogVersion;
    }

    public void setSyncedCatalogVersion(int syncedCatalogVersion) {
        this.syncedCatalogVersion = syncedCatalogVersion;
    }
}
