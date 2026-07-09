package vn.essvn.erpcafe.catalog.domain;

import java.util.UUID;

import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import vn.essvn.erpcafe.common.domain.AuditableEntity;

/**
 * A menu grouping (e.g. Coffee, Tea, Pastries), scoped to a company.
 * Soft-deletable master data.
 */
@Entity
@Table(name = "category", uniqueConstraints = @UniqueConstraint(name = "uk_category_company_name", columnNames = {"company_id", "name"}))
@SQLDelete(sql = "UPDATE category SET deleted = true WHERE id = ? AND version = ?")
@SQLRestriction("deleted = false")
public class Category extends AuditableEntity {

    @Column(name = "company_id", nullable = false)
    private UUID companyId;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "display_order", nullable = false)
    private int displayOrder = 0;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "deleted", nullable = false)
    private boolean deleted = false;

    protected Category() {
    }

    public Category(UUID companyId, String name, int displayOrder) {
        this.companyId = companyId;
        this.name = name;
        this.displayOrder = displayOrder;
    }

    public UUID getCompanyId() {
        return companyId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public int getDisplayOrder() {
        return displayOrder;
    }

    public void setDisplayOrder(int displayOrder) {
        this.displayOrder = displayOrder;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }
}
