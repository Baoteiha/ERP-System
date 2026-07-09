package vn.essvn.erpcafe.identity.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import vn.essvn.erpcafe.common.domain.BaseEntity;

/**
 * A fine-grained, system-wide authority (e.g. {@code branch:write},
 * {@code order:create}). Roles are bundles of permissions. The permission
 * catalog is synced from code at startup, so this is reference data.
 */
@Entity
@Table(name = "permission", uniqueConstraints = @UniqueConstraint(name = "uk_permission_name", columnNames = "name"))
public class Permission extends BaseEntity {

    @Column(name = "name", nullable = false, length = 64)
    private String name;

    @Column(name = "description")
    private String description;

    protected Permission() {
        // for JPA
    }

    public Permission(String name, String description) {
        this.name = name;
        this.description = description;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }
}
