package vn.essvn.erpcafe.inventory.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import vn.essvn.erpcafe.common.domain.BranchScopedEntity;

/**
 * Class: GoodsReceipt
 * Purpose: One delivery or replacement received against a purchase order, with who received it and when.
 */
@Entity
@Table(name = "goods_receipt")
public class GoodsReceipt extends BranchScopedEntity {

    @Column(name = "purchase_order_id", nullable = false)
    private UUID purchaseOrderId;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 16)
    private GoodsReceiptKind kind;

    @Column(name = "note")
    private String note;

    // not null: the key goes into the child's own INSERT, which row-level security admits only under a visible parent
    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @JoinColumn(name = "goods_receipt_id", nullable = false)
    private List<GoodsReceiptLine> lines = new ArrayList<>();

    protected GoodsReceipt() {
    }

    public GoodsReceipt(UUID purchaseOrderId, UUID branchId, GoodsReceiptKind kind, String note) {
        this.purchaseOrderId = purchaseOrderId;
        setBranchId(branchId);
        this.kind = kind;
        this.note = note;
    }

    public UUID getPurchaseOrderId() {
        return purchaseOrderId;
    }

    public GoodsReceiptKind getKind() {
        return kind;
    }

    public String getNote() {
        return note;
    }

    public List<GoodsReceiptLine> getLines() {
        return lines;
    }

    public void addLine(GoodsReceiptLine line) {
        lines.add(line);
    }
}
