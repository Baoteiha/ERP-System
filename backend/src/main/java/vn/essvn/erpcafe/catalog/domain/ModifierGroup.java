package vn.essvn.erpcafe.catalog.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import vn.essvn.erpcafe.common.domain.AuditableEntity;

/**
 * A group of choices attached to products (e.g. "Size", "Milk", "Temperature",
 * "Extras"), with selection rules. Company-scoped, soft-deletable.
 */
@Entity
@Table(name = "modifier_group", uniqueConstraints = @UniqueConstraint(name = "uk_modifier_group_company_name", columnNames = {"company_id", "name"}))
@SQLDelete(sql = "UPDATE modifier_group SET deleted = true WHERE id = ? AND version = ?")
@SQLRestriction("deleted = false")
public class ModifierGroup extends AuditableEntity {

    @Column(name = "company_id", nullable = false)
    private UUID companyId;

    @Column(name = "name", nullable = false)
    private String name;

    /** Minimum number of options the customer must pick (0 = optional). */
    @Column(name = "min_select", nullable = false)
    private int minSelect = 0;

    /** Maximum number of options selectable (1 = single-choice like Size). */
    @Column(name = "max_select", nullable = false)
    private int maxSelect = 1;

    @Column(name = "deleted", nullable = false)
    private boolean deleted = false;

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @JoinColumn(name = "modifier_group_id")
    @OrderBy("displayOrder ASC")
    private List<Modifier> modifiers = new ArrayList<>();

    protected ModifierGroup() {
    }

    public ModifierGroup(UUID companyId, String name, int minSelect, int maxSelect) {
        this.companyId = companyId;
        this.name = name;
        this.minSelect = minSelect;
        this.maxSelect = maxSelect;
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

    public int getMinSelect() {
        return minSelect;
    }

    public void setMinSelect(int minSelect) {
        this.minSelect = minSelect;
    }

    public int getMaxSelect() {
        return maxSelect;
    }

    public void setMaxSelect(int maxSelect) {
        this.maxSelect = maxSelect;
    }

    public boolean isRequired() {
        return minSelect > 0;
    }

    public List<Modifier> getModifiers() {
        return modifiers;
    }
}
