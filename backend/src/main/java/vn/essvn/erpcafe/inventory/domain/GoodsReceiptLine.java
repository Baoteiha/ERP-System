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
import vn.essvn.erpcafe.common.domain.BaseEntity;
import vn.essvn.erpcafe.common.domain.Money;
import vn.essvn.erpcafe.common.exception.BusinessRuleException;

/**
 * Class: GoodsReceiptLine
 * Purpose: What one purchase line received: delivered, damaged and lost, any extra and how it was taken, and the price paid.
 */
@Entity
@Table(name = "goods_receipt_line")
public class GoodsReceiptLine extends BaseEntity {

    @Column(name = "purchase_order_line_id", nullable = false)
    private UUID purchaseOrderLineId;

    /** In the line's ordering unit (packs, or its unit). Zero on a replacement. */
    @Column(name = "delivered_qty", nullable = false, precision = 19, scale = 4)
    private BigDecimal deliveredQty = BigDecimal.ZERO;

    /** In the line's unit. */
    @Column(name = "damaged_qty", nullable = false, precision = 19, scale = 4)
    private BigDecimal damagedQty = BigDecimal.ZERO;

    /** The damaged units that were paid for, in the line's unit: what the loss and a replacement count. */
    @Column(name = "loss_qty", nullable = false, precision = 19, scale = 4)
    private BigDecimal lossQty = BigDecimal.ZERO;

    @Column(name = "over_delivered_qty", nullable = false, precision = 19, scale = 4)
    private BigDecimal overDeliveredQty = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(name = "over_delivery_kind", length = 16)
    private OverDeliveryKind overDeliveryKind;

    /** The price paid per ordering unit. */
    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "amount", column = @Column(name = "unit_cost", precision = 19, scale = 4)),
            @AttributeOverride(name = "currency", column = @Column(name = "currency", length = 3))
    })
    private Money unitCost;

    /** The price paid per unit of the line, in {@code unitCost}'s currency: what stock and the loss use. */
    @Column(name = "cost_per_unit", nullable = false, precision = 19, scale = 4)
    private BigDecimal costPerUnit;

    @Column(name = "compensated_qty", nullable = false, precision = 19, scale = 4)
    private BigDecimal compensatedQty = BigDecimal.ZERO;

    /** On a replacement, the units it brought in, in the line's unit. */
    @Column(name = "replacement_qty", nullable = false, precision = 19, scale = 4)
    private BigDecimal replacementQty = BigDecimal.ZERO;

    @Column(name = "compensates_line_id")
    private UUID compensatesLineId;

    @Column(name = "note")
    private String note;

    protected GoodsReceiptLine() {
    }

    /** A delivered line, as {@code split} divided it. */
    public static GoodsReceiptLine delivered(UUID purchaseOrderLineId, BigDecimal delivered, BigDecimal damaged,
            ReceiptSplit split, OverDeliveryKind kind, Money unitCost, BigDecimal costPerUnit, String note) {
        GoodsReceiptLine line = new GoodsReceiptLine();
        line.purchaseOrderLineId = purchaseOrderLineId;
        line.deliveredQty = delivered;
        line.damagedQty = damaged;
        line.lossQty = split.lossQty();
        line.overDeliveredQty = split.overDeliveredQty();
        line.overDeliveryKind = kind;
        line.unitCost = unitCost;
        line.costPerUnit = costPerUnit;
        line.note = note;
        return line;
    }

    /** A replacement for {@code qty} units {@code lost} lost, valued as those units were. */
    public static GoodsReceiptLine replacement(GoodsReceiptLine lost, BigDecimal qty, String note) {
        GoodsReceiptLine line = new GoodsReceiptLine();
        line.purchaseOrderLineId = lost.purchaseOrderLineId;
        line.replacementQty = qty;
        line.unitCost = lost.unitCost;
        line.costPerUnit = lost.costPerUnit;
        line.compensatesLineId = lost.getId();
        line.note = note;
        return line;
    }

    /** Lost units a replacement may still cover. */
    public BigDecimal uncompensatedLoss() {
        return lossQty.subtract(compensatedQty);
    }

    /** Records {@code qty} replaced units; never more than are still lost. */
    public void compensate(BigDecimal qty) {
        if (qty == null || qty.signum() <= 0) {
            throw new BusinessRuleException("A replacement quantity must be positive");
        }
        if (qty.compareTo(uncompensatedLoss()) > 0) {
            throw new BusinessRuleException("A replacement can cover at most the %s still lost"
                    .formatted(uncompensatedLoss().stripTrailingZeros().toPlainString()));
        }
        compensatedQty = compensatedQty.add(qty);
    }

    /** What the loss still costs: lost units not yet replaced, at the price paid per unit. */
    public Money lossAmount() {
        return Money.of(uncompensatedLoss().multiply(costPerUnit), unitCost.getCurrency());
    }

    public UUID getPurchaseOrderLineId() {
        return purchaseOrderLineId;
    }

    public BigDecimal getDeliveredQty() {
        return deliveredQty;
    }

    public BigDecimal getDamagedQty() {
        return damagedQty;
    }

    public BigDecimal getLossQty() {
        return lossQty;
    }

    public BigDecimal getOverDeliveredQty() {
        return overDeliveredQty;
    }

    public OverDeliveryKind getOverDeliveryKind() {
        return overDeliveryKind;
    }

    public Money getUnitCost() {
        return unitCost;
    }

    public BigDecimal getCostPerUnit() {
        return costPerUnit;
    }

    public BigDecimal getCompensatedQty() {
        return compensatedQty;
    }

    public BigDecimal getReplacementQty() {
        return replacementQty;
    }

    public UUID getCompensatesLineId() {
        return compensatesLineId;
    }

    public String getNote() {
        return note;
    }
}
