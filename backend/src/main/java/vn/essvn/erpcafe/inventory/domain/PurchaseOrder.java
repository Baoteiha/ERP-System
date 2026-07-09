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
import vn.essvn.erpcafe.common.exception.BusinessRuleException;

/**
 * A purchase order raised at a branch to a supplier, with a status lifecycle
 * (DRAFT → SENT → PARTIALLY_RECEIVED → RECEIVED, or CANCELLED). Branch-scoped.
 */
@Entity
@Table(name = "purchase_order")
public class PurchaseOrder extends BranchScopedEntity {

    @Column(name = "supplier_id", nullable = false)
    private UUID supplierId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private PurchaseOrderStatus status = PurchaseOrderStatus.DRAFT;

    @Column(name = "note")
    private String note;

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @JoinColumn(name = "purchase_order_id")
    private List<PurchaseOrderLine> lines = new ArrayList<>();

    protected PurchaseOrder() {
    }

    public PurchaseOrder(UUID supplierId, UUID branchId, String note) {
        this.supplierId = supplierId;
        setBranchId(branchId);
        this.note = note;
    }

    public UUID getSupplierId() {
        return supplierId;
    }

    public PurchaseOrderStatus getStatus() {
        return status;
    }

    public String getNote() {
        return note;
    }

    public List<PurchaseOrderLine> getLines() {
        return lines;
    }

    public void addLine(PurchaseOrderLine line) {
        this.lines.add(line);
    }

    // --- state transitions ---

    public void send() {
        requireStatus(PurchaseOrderStatus.DRAFT, "sent");
        this.status = PurchaseOrderStatus.SENT;
    }

    public void cancel() {
        if (status == PurchaseOrderStatus.RECEIVED || status == PurchaseOrderStatus.CANCELLED) {
            throw new BusinessRuleException("Cannot cancel a %s order".formatted(status));
        }
        this.status = PurchaseOrderStatus.CANCELLED;
    }

    /** Recomputes status after a receipt, based on how much of the order is now received. */
    public void refreshReceiptStatus() {
        if (status == PurchaseOrderStatus.CANCELLED) {
            throw new BusinessRuleException("Cannot receive against a cancelled order");
        }
        boolean allReceived = lines.stream().allMatch(PurchaseOrderLine::isFullyReceived);
        this.status = allReceived ? PurchaseOrderStatus.RECEIVED : PurchaseOrderStatus.PARTIALLY_RECEIVED;
    }

    private void requireStatus(PurchaseOrderStatus expected, String action) {
        if (status != expected) {
            throw new BusinessRuleException(
                    "Order must be %s to be %s (was %s)".formatted(expected, action, status));
        }
    }
}
