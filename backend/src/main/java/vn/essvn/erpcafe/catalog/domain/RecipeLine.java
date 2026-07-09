package vn.essvn.erpcafe.catalog.domain;

import java.math.BigDecimal;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import vn.essvn.erpcafe.common.domain.BaseEntity;

/**
 * One ingredient line of a {@link Recipe}: how much of an ingredient the base
 * product consumes. References an ingredient by id (validated once inventory
 * exists). The FK to recipe is managed by {@code Recipe.lines}.
 */
@Entity
@Table(name = "recipe_line")
public class RecipeLine extends BaseEntity {

    @Column(name = "ingredient_id", nullable = false)
    private UUID ingredientId;

    @Column(name = "quantity", nullable = false, precision = 19, scale = 4)
    private BigDecimal quantity;

    @Column(name = "unit", nullable = false, length = 16)
    private String unit;

    protected RecipeLine() {
    }

    public RecipeLine(UUID ingredientId, BigDecimal quantity, String unit) {
        this.ingredientId = ingredientId;
        this.quantity = quantity;
        this.unit = unit;
    }

    public UUID getIngredientId() {
        return ingredientId;
    }

    public BigDecimal getQuantity() {
        return quantity;
    }

    public String getUnit() {
        return unit;
    }
}
