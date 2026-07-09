package vn.essvn.erpcafe.inventory.domain;

import java.math.BigDecimal;
import java.util.UUID;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import vn.essvn.erpcafe.common.domain.BranchScopedEntity;
import vn.essvn.erpcafe.common.domain.Money;

/**
 * An immutable, append-only ledger entry: one change to an ingredient's stock at
 * a branch. Never updated — corrections are new movements. {@code refType}/{@code refId}
 * link a movement to what caused it (a purchase order, an order, an adjustment).
 * The audit columns record who/when.
 */
@Entity
@Table(name = "stock_movement")
public class StockMovement extends BranchScopedEntity {

    @Column(name = "ingredient_id", nullable = false)
    private UUID ingredientId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 24)
    private MovementType type;

    /** Signed quantity in the ingredient's base unit (positive = in, negative = out). */
    @Column(name = "quantity", nullable = false, precision = 19, scale = 4)
    private BigDecimal quantity;

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "amount", column = @Column(name = "unit_cost", precision = 19, scale = 4)),
            @AttributeOverride(name = "currency", column = @Column(name = "currency", length = 3))
    })
    private Money unitCost;

    @Column(name = "ref_type", length = 32)
    private String refType;

    @Column(name = "ref_id")
    private UUID refId;

    @Column(name = "note")
    private String note;

    protected StockMovement() {
    }

    public StockMovement(UUID ingredientId, UUID branchId, MovementType type, BigDecimal quantity,
            Money unitCost, String refType, UUID refId, String note) {
        this.ingredientId = ingredientId;
        setBranchId(branchId);
        this.type = type;
        this.quantity = quantity;
        this.unitCost = unitCost;
        this.refType = refType;
        this.refId = refId;
        this.note = note;
    }

    public UUID getIngredientId() {
        return ingredientId;
    }

    public MovementType getType() {
        return type;
    }

    public BigDecimal getQuantity() {
        return quantity;
    }

    public Money getUnitCost() {
        return unitCost;
    }

    public String getRefType() {
        return refType;
    }

    public UUID getRefId() {
        return refId;
    }

    public String getNote() {
        return note;
    }
}
