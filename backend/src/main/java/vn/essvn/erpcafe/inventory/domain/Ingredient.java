package vn.essvn.erpcafe.inventory.domain;

import java.util.UUID;

import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import vn.essvn.erpcafe.common.domain.AuditableEntity;

/**
 * A raw material a company stocks and consumes (coffee beans, oat milk, cups).
 * The {@code baseUnit} is the unit stock is counted in (g, ml, unit).
 * Company-scoped master data; recipes reference these by id. Soft-deletable.
 */
@Entity
@Table(name = "ingredient", uniqueConstraints = @UniqueConstraint(name = "uk_ingredient_company_name", columnNames = {"company_id", "name"}))
@SQLDelete(sql = "UPDATE ingredient SET deleted = true WHERE id = ? AND version = ?")
@SQLRestriction("deleted = false")
public class Ingredient extends AuditableEntity {

    @Column(name = "company_id", nullable = false)
    private UUID companyId;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "base_unit", nullable = false, length = 16)
    private String baseUnit;

    @Column(name = "category", length = 64)
    private String category;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "deleted", nullable = false)
    private boolean deleted = false;

    protected Ingredient() {
    }

    public Ingredient(UUID companyId, String name, String baseUnit, String category) {
        this.companyId = companyId;
        this.name = name;
        this.baseUnit = baseUnit;
        this.category = category;
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

    public String getBaseUnit() {
        return baseUnit;
    }

    public void setBaseUnit(String baseUnit) {
        this.baseUnit = baseUnit;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }
}
