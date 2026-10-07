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
 * One supplier-item line of a {@link PurchaseOrder}: how much was ordered, how much
 * has been received so far, and the agreed unit cost. Quantities and cost are all
 * counted in the line's ordering unit: its pack when it has one, otherwise its own {@code unit}
 * (taken from an {@link Item}), not the ingredient's base unit; the
 * ledger converts on receipt. The FK to the order is managed by {@code PurchaseOrder.lines}.
 */
@Entity
@Table(name = "purchase_order_line")
public class PurchaseOrderLine extends BaseEntity {

    @Column(name = "ingredient_id", nullable = false)
    private UUID ingredientId;

    /** The item the line was created from; null only on legacy lines made from an ingredient and a unit, since new lines are always items. */
    @Column(name = "item_id")
    private UUID itemId;

    @Column(name = "unit", nullable = false, length = 16)
    private String unit;

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

    /** The item's pack when the order was placed ("hộp"), or null; a copy, so later item edits never reach it. */
    @Column(name = "pack_label", length = 32)
    private String packLabel;

    @Column(name = "pack_size", precision = 19, scale = 4)
    private BigDecimal packSize;

    protected PurchaseOrderLine() {
    }

    public PurchaseOrderLine(UUID itemId, UUID ingredientId, String unit, BigDecimal orderedQty, Money unitCost,
            String packLabel, BigDecimal packSize) {
        this.itemId = itemId;
        this.ingredientId = ingredientId;
        this.unit = unit;
        this.orderedQty = orderedQty;
        this.unitCost = unitCost;
        this.packLabel = packLabel;
        this.packSize = packSize;
    }

    public UUID getIngredientId() {
        return ingredientId;
    }

    public UUID getItemId() {
        return itemId;
    }

    public String getUnit() {
        return unit;
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

    public String getPackLabel() {
        return packLabel;
    }

    public BigDecimal getPackSize() {
        return packSize;
    }

    public boolean hasPack() {
        return packLabel != null;
    }

    /** How many of {@code unit} one ordering unit holds: the pack's size, or 1 for a line without a pack. */
    public BigDecimal unitsPerOrderingUnit() {
        return hasPack() ? packSize : BigDecimal.ONE;
    }

    /** A charged over-delivery: more arrived than was ordered and it is paid for, so the order grows. */
    public void growOrdered(BigDecimal extra) {
        this.orderedQty = this.orderedQty.add(extra);
    }

    public Money getUnitCost() {
        return unitCost;
    }
}
