package vn.essvn.erpcafe.catalog.domain;

import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import vn.essvn.erpcafe.common.domain.AuditableEntity;
import vn.essvn.erpcafe.common.domain.Money;

/**
 * A single option within a {@link ModifierGroup} (e.g. "Large", "Oat milk",
 * "Extra shot"). Carries a price delta and, optionally, ingredient-consumption
 * deltas (see {@link ModifierRecipeLine}) so stock deduction stays accurate.
 */
@Entity
@Table(name = "modifier")
public class Modifier extends AuditableEntity {

    @Column(name = "name", nullable = false)
    private String name;

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "amount", column = @Column(name = "price_delta", precision = 19, scale = 4)),
            @AttributeOverride(name = "currency", column = @Column(name = "currency", length = 3))
    })
    private Money priceDelta;

    @Column(name = "display_order", nullable = false)
    private int displayOrder = 0;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @JoinColumn(name = "modifier_id")
    private List<ModifierRecipeLine> recipeLines = new ArrayList<>();

    protected Modifier() {
    }

    public Modifier(String name, Money priceDelta, int displayOrder) {
        this.name = name;
        this.priceDelta = priceDelta;
        this.displayOrder = displayOrder;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Money getPriceDelta() {
        return priceDelta;
    }

    public void setPriceDelta(Money priceDelta) {
        this.priceDelta = priceDelta;
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

    public List<ModifierRecipeLine> getRecipeLines() {
        return recipeLines;
    }
}
