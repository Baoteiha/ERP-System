package vn.essvn.erpcafe.inventory.domain;

import java.math.BigDecimal;
import java.util.UUID;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import vn.essvn.erpcafe.common.domain.BaseEntity;
import vn.essvn.erpcafe.common.domain.Money;

/**
 * One ingredient line of a {@link PurchaseOrder}: how much was ordered, how much
 * has been received so far, and the agreed unit cost. The FK to the order is
 * managed by {@code PurchaseOrder.lines}.
 */
@Entity
@Table(name = "purchase_order_line")
public class PurchaseOrderLine extends BaseEntity {

    @Column(name = "ingredient_id", nullable = false)
    private UUID ingredientId;

    @Column(name = "ordered_qty", nullable = false, precision = 19, scale = 4)
    private BigDecimal orderedQty;

    @Column(name = "received_qty", nullable = false, precision = 19, scale = 4)
    private BigDecimal receivedQty = BigDecimal.ZERO;

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "amount", column = @Column(name = "unit_cost", precision = 19, scale = 4)),
            @AttributeOverride(name = "currency", column = @Column(name = "currency", length = 3))
    })
    private Money unitCost;

    protected PurchaseOrderLine() {
    }

    public PurchaseOrderLine(UUID ingredientId, BigDecimal orderedQty, Money unitCost) {
        this.ingredientId = ingredientId;
        this.orderedQty = orderedQty;
        this.unitCost = unitCost;
    }

    public UUID getIngredientId() {
        return ingredientId;
    }

    public BigDecimal getOrderedQty() {
        return orderedQty;
    }

    public BigDecimal getReceivedQty() {
        return receivedQty;
    }

    public void receive(BigDecimal quantity) {
        this.receivedQty = this.receivedQty.add(quantity);
    }

    public BigDecimal outstandingQty() {
        return orderedQty.subtract(receivedQty);
    }

    public boolean isFullyReceived() {
        return receivedQty.compareTo(orderedQty) >= 0;
    }

    public Money getUnitCost() {
        return unitCost;
    }
}
