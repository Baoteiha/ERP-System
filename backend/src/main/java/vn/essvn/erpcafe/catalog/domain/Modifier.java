package vn.essvn.erpcafe.catalog.domain;

import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import vn.essvn.erpcafe.common.domain.AuditableEntity;
import vn.essvn.erpcafe.common.domain.Money;

/**
 * A single option within a {@link ModifierGroup} (e.g. "Large", "Oat milk",
 * "Extra shot"). Carries a price delta and, optionally, ingredient-consumption
 * deltas (see {@link #getRecipeLines()}) so stock deduction stays accurate.
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

    /**
     * Signed changes to consumption: positive adds, negative removes (extra shot = +7 g coffee;
     * oat milk = -200 ml dairy and +200 ml oat, as two lines). Not versioned (ADR-0010): a sale
     * applies them as they stand when it completes.
     */
    // not null: the key goes into the child's own INSERT, which row-level security admits only under a visible parent
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "modifier_recipe_line", joinColumns = @JoinColumn(name = "modifier_id", nullable = false))
    @AttributeOverride(name = "quantity",
            column = @Column(name = "quantity_delta", nullable = false, precision = 19, scale = 4))
    private List<ConsumptionLine> recipeLines = new ArrayList<>();

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

    public List<ConsumptionLine> getRecipeLines() {
        return recipeLines;
    }
}
