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
import vn.essvn.erpcafe.common.domain.Unit;
import vn.essvn.erpcafe.common.exception.BusinessRuleException;
import vn.essvn.erpcafe.common.exception.ResourceNotFoundException;
import vn.essvn.erpcafe.inventory.application.PurchaseInputs.LineInput;
import vn.essvn.erpcafe.inventory.application.PurchaseInputs.ReceiptInput;
import vn.essvn.erpcafe.inventory.domain.Ingredient;
import vn.essvn.erpcafe.inventory.domain.Item;
import vn.essvn.erpcafe.inventory.domain.PurchaseOrder;
import vn.essvn.erpcafe.inventory.domain.PurchaseOrderLine;
import vn.essvn.erpcafe.inventory.domain.PurchaseOrderStatus;
import vn.essvn.erpcafe.inventory.domain.Supplier;
import vn.essvn.erpcafe.inventory.persistence.IngredientRepository;
import vn.essvn.erpcafe.inventory.persistence.ItemRepository;
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
    private final ItemRepository itemRepository;
    private final StockLedgerService ledger;

    public PurchaseOrderService(PurchaseOrderRepository purchaseOrderRepository,
            SupplierRepository supplierRepository, IngredientRepository ingredientRepository,
            ItemRepository itemRepository, StockLedgerService ledger) {
        this.purchaseOrderRepository = purchaseOrderRepository;
        this.supplierRepository = supplierRepository;
        this.ingredientRepository = ingredientRepository;
        this.itemRepository = itemRepository;
        this.ledger = ledger;
    }

    @Transactional(readOnly = true)
    public Page<PurchaseOrder> list(Pageable pageable) {
        return purchaseOrderRepository.findByBranchId(BranchContext.require(), pageable);
    }

    @Transactional(readOnly = true)
    public PurchaseOrder get(UUID id) {
        // Scope to the active branch: a bare findById would let a user read, send,
        // receive, or cancel a PO in another branch/company by id (IDOR). Every
        // caller (get/send/receive/cancel) already runs with an accessible branch.
        return purchaseOrderRepository.findByIdAndBranchId(id, BranchContext.require())
                .orElseThrow(() -> ResourceNotFoundException.of("PurchaseOrder", id));
    }

    public PurchaseOrder create(UUID supplierId, String note, List<LineInput> lines) {
        requireActiveSupplier(supplierId);
        if (lines == null || lines.isEmpty()) {
            throw new BusinessRuleException("A purchase order needs at least one line");
        }
        PurchaseOrder order = new PurchaseOrder(supplierId, BranchContext.require(), note);
        for (LineInput line : lines) {
            order.addLine(toLine(supplierId, line));
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
        requireActiveSupplier(order.getSupplierId());
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
            requireActive(requireIngredient(line.getIngredientId()));
            BigDecimal qty = receipt.receivedQty();
            if (qty == null || qty.signum() <= 0) {
                throw new BusinessRuleException("Received quantity must be positive");
            }
            // The line is counted and priced in its own unit; a delivery may be counted in any unit of
            // the same dimension (ADR-0006) and defaults to the line's. The outstanding check speaks the
            // line's unit, while the ledger takes the receipt's unit and a cost per BASE unit, so costs
            // are re-rated through its single path (210,000 per kg is 210 per g).
            UUID branchId = order.getBranchId();
            Unit lineUnit = Unit.parse(line.getUnit());
            Unit receiptUnit = receipt.unit() == null || receipt.unit().isBlank()
                    ? lineUnit : Unit.parse(receipt.unit());
            BigDecimal lineQty = Unit.convert(qty, receiptUnit, lineUnit);
            if (lineQty.compareTo(line.outstandingQty()) > 0) {
                throw new BusinessRuleException("Received quantity exceeds outstanding for line " + line.getId());
            }
            BigDecimal unitCost = receipt.unitCostOverride() != null
                    ? ledger.toBaseRate(branchId, line.getIngredientId(), receipt.unitCostOverride(),
                            receiptUnit.symbol())
                    : ledger.toBaseRate(branchId, line.getIngredientId(), line.getUnitCost().getAmount(),
                            lineUnit.symbol());
            line.receive(lineQty);
            ledger.receive(branchId, line.getIngredientId(), qty, receiptUnit.symbol(), Money.of(unitCost),
                    REF_TYPE, order.getId());
        }
        order.refreshReceiptStatus();
        return order;
    }

    // Every line is a supplier item: it fixes the ingredient, the unit and the pack, so the order says
    // exactly what the supplier sends. The old "ingredient + unit" line is refused with a clear message.
    private PurchaseOrderLine toLine(UUID supplierId, LineInput line) {
        if (line.itemId() == null) {
            throw new BusinessRuleException("A purchase line must be a supplier item");
        }
        if (line.ingredientId() != null) {
            throw new BusinessRuleException("A line is a supplier item; leave the ingredient out");
        }
        if (line.unit() != null) {
            throw new BusinessRuleException(
                    "An item line takes its unit from the item; leave the unit out, even if blank");
        }
        // Items are tenant-owned, so another company's item is simply not found.
        Item item = itemRepository.findById(line.itemId())
                .orElseThrow(() -> ResourceNotFoundException.of("Item", line.itemId()));
        if (!item.isActive()) {
            throw new BusinessRuleException("Item " + item.getName() + " is inactive");
        }
        requireActive(requireIngredient(item.getIngredientId()));
        if (!item.getSupplierId().equals(supplierId)) {
            throw new BusinessRuleException("Item " + item.getName() + " is sold by another supplier");
        }
        if (item.hasPack()) {
            requireWholePacks(line.orderedQty(), item.getPackLabel());
        }
        return new PurchaseOrderLine(item.getId(), item.getIngredientId(), item.getUnit(),
                line.orderedQty(), Money.of(line.unitCost()), item.getPackLabel(), item.getPackSize());
    }

    /** Packs are bought and received whole: 5 hộp, never 2.5. */
    static void requireWholePacks(BigDecimal qty, String packLabel) {
        if (qty != null && qty.stripTrailingZeros().scale() > 0) {
            throw new BusinessRuleException("Packs are counted whole: %s %s"
                    .formatted(qty.stripTrailingZeros().toPlainString(), packLabel));
        }
    }

    private void requireActiveSupplier(UUID supplierId) {
        Supplier supplier = supplierRepository.findById(supplierId)
                .orElseThrow(() -> ResourceNotFoundException.of("Supplier", supplierId));
        if (!supplier.isActive()) {
            throw new BusinessRuleException("Supplier " + supplier.getName() + " is inactive");
        }
    }

    private static Ingredient requireActive(Ingredient ingredient) {
        if (!ingredient.isActive()) {
            throw new BusinessRuleException("Ingredient " + ingredient.getName() + " is inactive");
        }
        return ingredient;
    }

    private Ingredient requireIngredient(UUID ingredientId) {
        return ingredientRepository.findById(ingredientId)
                .orElseThrow(() -> ResourceNotFoundException.of("Ingredient", ingredientId));
    }
}
