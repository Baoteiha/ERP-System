package vn.essvn.erpcafe.catalog.domain;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import vn.essvn.erpcafe.common.domain.AuditableEntity;
import vn.essvn.erpcafe.common.domain.Money;

/**
 * A menu item, scoped to a company. Carries a company-wide base price;
 * per-branch price/availability overrides live in {@link ProductBranchAvailability}.
 * A product may be attached to modifier groups (Size, Milk, …). Soft-deletable.
 */
@Entity
@Table(name = "product", uniqueConstraints = @UniqueConstraint(name = "uk_product_company_sku", columnNames = {"company_id", "sku"}))
@SQLDelete(sql = "UPDATE product SET deleted = true WHERE id = ? AND version = ?")
@SQLRestriction("deleted = false")
public class Product extends AuditableEntity {

    @Column(name = "company_id", nullable = false)
    private UUID companyId;

    @Column(name = "category_id", nullable = false)
    private UUID categoryId;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "sku", nullable = false, length = 64)
    private String sku;

    @Column(name = "description")
    private String description;

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "amount", column = @Column(name = "base_price", precision = 19, scale = 4)),
            @AttributeOverride(name = "currency", column = @Column(name = "currency", length = 3))
    })
    private Money basePrice;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "deleted", nullable = false)
    private boolean deleted = false;

    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(name = "product_modifier_group",
            joinColumns = @JoinColumn(name = "product_id"),
            inverseJoinColumns = @JoinColumn(name = "modifier_group_id"))
    private Set<ModifierGroup> modifierGroups = new HashSet<>();

    protected Product() {
    }

    public Product(UUID companyId, UUID categoryId, String name, String sku, Money basePrice) {
        this.companyId = companyId;
        this.categoryId = categoryId;
        this.name = name;
        this.sku = sku;
        this.basePrice = basePrice;
    }

    public UUID getCompanyId() {
        return companyId;
    }

    public UUID getCategoryId() {
        return categoryId;
    }

    public void setCategoryId(UUID categoryId) {
        this.categoryId = categoryId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getSku() {
        return sku;
    }

    public void setSku(String sku) {
        this.sku = sku;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Money getBasePrice() {
        return basePrice;
    }

    public void setBasePrice(Money basePrice) {
        this.basePrice = basePrice;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public Set<ModifierGroup> getModifierGroups() {
        return modifierGroups;
    }

    public void setModifierGroups(Set<ModifierGroup> modifierGroups) {
        this.modifierGroups = modifierGroups;
    }
}
