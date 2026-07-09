package vn.essvn.erpcafe.catalog.domain;

import java.math.BigDecimal;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import vn.essvn.erpcafe.common.domain.BaseEntity;

/**
 * How a {@link Modifier} changes ingredient consumption, as a signed delta
 * (e.g. extra shot = +7g coffee; oat milk = -200ml dairy plus +200ml oat as two
 * lines). References an ingredient by id — validated once the inventory module
 * exists. The FK to modifier is managed by {@code Modifier.recipeLines}.
 */
@Entity
@Table(name = "modifier_recipe_line")
public class ModifierRecipeLine extends BaseEntity {

    @Column(name = "ingredient_id", nullable = false)
    private UUID ingredientId;

    /** Signed quantity change in {@link #unit}: positive adds, negative removes. */
    @Column(name = "quantity_delta", nullable = false, precision = 19, scale = 4)
    private BigDecimal quantityDelta;

    @Column(name = "unit", nullable = false, length = 16)
    private String unit;

    protected ModifierRecipeLine() {
    }

    public ModifierRecipeLine(UUID ingredientId, BigDecimal quantityDelta, String unit) {
        this.ingredientId = ingredientId;
        this.quantityDelta = quantityDelta;
        this.unit = unit;
    }

    public UUID getIngredientId() {
        return ingredientId;
    }

    public BigDecimal getQuantityDelta() {
        return quantityDelta;
    }

    public String getUnit() {
        return unit;
    }
}
