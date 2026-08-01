package vn.essvn.erpcafe.inventory.application;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.essvn.erpcafe.common.context.BranchContext;
import vn.essvn.erpcafe.common.domain.Money;
import vn.essvn.erpcafe.common.exception.BusinessRuleException;
import vn.essvn.erpcafe.common.exception.ResourceNotFoundException;
import vn.essvn.erpcafe.identity.security.CurrentUser;
import vn.essvn.erpcafe.inventory.application.PurchaseInputs.LineInput;
import vn.essvn.erpcafe.inventory.application.PurchaseInputs.ReceiptInput;
import vn.essvn.erpcafe.inventory.domain.Ingredient;
import vn.essvn.erpcafe.inventory.domain.PurchaseOrder;
import vn.essvn.erpcafe.inventory.domain.PurchaseOrderLine;
import vn.essvn.erpcafe.inventory.domain.PurchaseOrderStatus;
import vn.essvn.erpcafe.inventory.persistence.IngredientRepository;
import vn.essvn.erpcafe.inventory.persistence.PurchaseOrderRepository;
import vn.essvn.erpcafe.inventory.persistence.SupplierRepository;

/**
 * Purchase-order lifecycle for the active branch: create (DRAFT) → send (SENT) →
 * receive (PARTIALLY_RECEIVED / RECEIVED) or cancel. Receiving raises stock and
 * updates moving-average cost via {@link StockLedgerService}.
 */
@Service
@Transactional
public class PurchaseOrderService {

    public static final String REF_TYPE = "PURCHASE_ORDER";

    private final PurchaseOrderRepository purchaseOrderRepository;
    private final SupplierRepository supplierRepository;
    private final IngredientRepository ingredientRepository;
    private final StockLedgerService ledger;
    private final CurrentUser currentUser;

    public PurchaseOrderService(PurchaseOrderRepository purchaseOrderRepository,
            SupplierRepository supplierRepository, IngredientRepository ingredientRepository,
            StockLedgerService ledger, CurrentUser currentUser) {
        this.purchaseOrderRepository = purchaseOrderRepository;
        this.supplierRepository = supplierRepository;
        this.ingredientRepository = ingredientRepository;
        this.ledger = ledger;
        this.currentUser = currentUser;
    }

    @Transactional(readOnly = true)
    public Page<PurchaseOrder> list(Pageable pageable) {
        return purchaseOrderRepository.findByBranchId(activeBranch(), pageable);
    }

    @Transactional(readOnly = true)
    public PurchaseOrder get(UUID id) {
        return purchaseOrderRepository.findById(id)
                .orElseThrow(() -> ResourceNotFoundException.of("PurchaseOrder", id));
    }

    public PurchaseOrder create(UUID supplierId, String note, List<LineInput> lines) {
        UUID companyId = currentUser.require().companyId();
        requireSupplier(supplierId, companyId);
        if (lines == null || lines.isEmpty()) {
            throw new BusinessRuleException("A purchase order needs at least one line");
        }
        PurchaseOrder order = new PurchaseOrder(supplierId, activeBranch(), note);
        for (LineInput line : lines) {
            requireIngredient(line.ingredientId(), companyId);
            order.addLine(new PurchaseOrderLine(line.ingredientId(), line.orderedQty(), Money.of(line.unitCost())));
        }
        return purchaseOrderRepository.save(order);
    }

    public PurchaseOrder send(UUID id) {
        PurchaseOrder order = get(id);
        order.send();
        return order;
    }

    public PurchaseOrder cancel(UUID id) {
        PurchaseOrder order = get(id);
        order.cancel();
        return order;
    }

    /** Receives goods against a PO's lines: updates received quantities, raises stock, refreshes status. */
    public PurchaseOrder receive(UUID id, List<ReceiptInput> receipts) {
        PurchaseOrder order = get(id);
        if (order.getStatus() != PurchaseOrderStatus.SENT
                && order.getStatus() != PurchaseOrderStatus.PARTIALLY_RECEIVED) {
            throw new BusinessRuleException("Order must be SENT to receive (was %s)".formatted(order.getStatus()));
        }
        // Resolve every receipt to its PO line up front, then process in ingredientId
        // order. Stock rows must be locked in the same global order on every path or a
        // goods receipt and an order completion touching the same two ingredients can
        // deadlock — see StockLedgerService.normalize.
        record Resolved(ReceiptInput receipt, PurchaseOrderLine line) {
        }
        List<Resolved> resolved = new ArrayList<>();
        for (ReceiptInput receipt : receipts) {
            PurchaseOrderLine line = order.getLines().stream()
                    .filter(l -> l.getId().equals(receipt.lineId()))
                    .findFirst()
                    .orElseThrow(() -> ResourceNotFoundException.of("PurchaseOrderLine", receipt.lineId()));
            resolved.add(new Resolved(receipt, line));
        }
        resolved.sort(Comparator.comparing(r -> r.line().getIngredientId()));

        for (Resolved entry : resolved) {
            ReceiptInput receipt = entry.receipt();
            PurchaseOrderLine line = entry.line();
            BigDecimal qty = receipt.receivedQty();
            if (qty == null || qty.signum() <= 0) {
                throw new BusinessRuleException("Received quantity must be positive");
            }
            if (qty.compareTo(line.outstandingQty()) > 0) {
                throw new BusinessRuleException("Received quantity exceeds outstanding for line " + line.getId());
            }
            Money unitCost = receipt.unitCostOverride() != null
                    ? Money.of(receipt.unitCostOverride())
                    : line.getUnitCost();
            line.receive(qty);
            ledger.receive(order.getBranchId(), line.getIngredientId(), qty, unitCost, REF_TYPE, order.getId());
        }
        order.refreshReceiptStatus();
        return order;
    }

    private void requireSupplier(UUID supplierId, UUID companyId) {
        var supplier = supplierRepository.findById(supplierId)
                .orElseThrow(() -> ResourceNotFoundException.of("Supplier", supplierId));
        if (!supplier.getCompanyId().equals(companyId)) {
            throw new BusinessRuleException("Supplier belongs to a different company");
        }
    }

    private void requireIngredient(UUID ingredientId, UUID companyId) {
        Ingredient ingredient = ingredientRepository.findById(ingredientId)
                .orElseThrow(() -> ResourceNotFoundException.of("Ingredient", ingredientId));
        if (!ingredient.getCompanyId().equals(companyId)) {
            throw new BusinessRuleException("Ingredient belongs to a different company");
        }
    }

    private UUID activeBranch() {
        return BranchContext.current()
                .orElseThrow(() -> new BusinessRuleException("Active branch required (set X-Branch-Id header)"));
    }
}
