package vn.essvn.erpcafe.inventory.domain;

import java.math.BigDecimal;
import java.util.UUID;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import vn.essvn.erpcafe.common.domain.BranchScopedEntity;
import vn.essvn.erpcafe.common.domain.Money;

/**
 * The current stock of one ingredient at one branch: on-hand quantity, reorder
 * level, and moving-average unit cost. This is a cached projection of the
 * {@link StockMovement} ledger (the ledger is the source of truth).
 */
@Entity
@Table(name = "stock_item", uniqueConstraints = @UniqueConstraint(name = "uk_stock_item_ingredient_branch", columnNames = {"ingredient_id", "branch_id"}))
public class StockItem extends BranchScopedEntity {

    @Column(name = "ingredient_id", nullable = false)
    private UUID ingredientId;

    @Column(name = "quantity_on_hand", nullable = false, precision = 19, scale = 4)
    private BigDecimal quantityOnHand = BigDecimal.ZERO;

    @Column(name = "reorder_level", nullable = false, precision = 19, scale = 4)
    private BigDecimal reorderLevel = BigDecimal.ZERO;

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "amount", column = @Column(name = "avg_unit_cost", precision = 19, scale = 4)),
            @AttributeOverride(name = "currency", column = @Column(name = "currency", length = 3))
    })
    private Money avgUnitCost = Money.zero();

    protected StockItem() {
    }

    public StockItem(UUID ingredientId, UUID branchId) {
        this.ingredientId = ingredientId;
        setBranchId(branchId);
    }

    public UUID getIngredientId() {
        return ingredientId;
    }

    public BigDecimal getQuantityOnHand() {
        return quantityOnHand;
    }

    public void setQuantityOnHand(BigDecimal quantityOnHand) {
        this.quantityOnHand = quantityOnHand;
    }

    public BigDecimal getReorderLevel() {
        return reorderLevel;
    }

    public void setReorderLevel(BigDecimal reorderLevel) {
        this.reorderLevel = reorderLevel;
    }

    public Money getAvgUnitCost() {
        return avgUnitCost;
    }

    public void setAvgUnitCost(Money avgUnitCost) {
        this.avgUnitCost = avgUnitCost;
    }

    public boolean isBelowReorderLevel() {
        return quantityOnHand.compareTo(reorderLevel) < 0;
    }
}
