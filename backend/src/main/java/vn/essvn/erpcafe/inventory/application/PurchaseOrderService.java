package vn.essvn.erpcafe.inventory.application;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.essvn.erpcafe.common.context.BranchContext;
import vn.essvn.erpcafe.common.domain.Money;
import vn.essvn.erpcafe.common.exception.BusinessRuleException;
import vn.essvn.erpcafe.common.exception.ResourceNotFoundException;
import vn.essvn.erpcafe.inventory.application.PurchaseInputs.CompensationInput;
import vn.essvn.erpcafe.inventory.application.PurchaseInputs.LineInput;
import vn.essvn.erpcafe.inventory.application.PurchaseInputs.ReceiptInput;
import vn.essvn.erpcafe.inventory.domain.GoodsReceipt;
import vn.essvn.erpcafe.inventory.domain.Ingredient;
import vn.essvn.erpcafe.inventory.domain.Item;
import vn.essvn.erpcafe.inventory.domain.PurchaseOrder;
import vn.essvn.erpcafe.inventory.domain.PurchaseOrderLine;
import vn.essvn.erpcafe.inventory.domain.Supplier;
import vn.essvn.erpcafe.inventory.persistence.GoodsReceiptRepository;
import vn.essvn.erpcafe.inventory.persistence.IngredientRepository;
import vn.essvn.erpcafe.inventory.persistence.ItemRepository;
import vn.essvn.erpcafe.inventory.persistence.PurchaseOrderRepository;
import vn.essvn.erpcafe.inventory.persistence.SupplierRepository;

/**
 * Purchase-order lifecycle for the active branch: create (DRAFT) → send (SENT) →
 * receive (PARTIALLY_RECEIVED / RECEIVED) or cancel. Receiving goes through {@link GoodsReceivingService}.
 */
@Service
@Transactional
public class PurchaseOrderService {

    private final PurchaseOrderRepository purchaseOrderRepository;
    private final SupplierRepository supplierRepository;
    private final IngredientRepository ingredientRepository;
    private final ItemRepository itemRepository;
    private final GoodsReceivingService receiving;
    private final GoodsReceiptRepository receiptRepository;

    public PurchaseOrderService(PurchaseOrderRepository purchaseOrderRepository,
            SupplierRepository supplierRepository, IngredientRepository ingredientRepository,
            ItemRepository itemRepository, GoodsReceivingService receiving,
            GoodsReceiptRepository receiptRepository) {
        this.purchaseOrderRepository = purchaseOrderRepository;
        this.supplierRepository = supplierRepository;
        this.ingredientRepository = ingredientRepository;
        this.itemRepository = itemRepository;
        this.receiving = receiving;
        this.receiptRepository = receiptRepository;
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

    /** Records a purchase already paid and carried in: an order of kind RETAIL, received in full in the same save. */
    public PurchaseOrder retail(UUID supplierId, String note, List<LineInput> lines) {
        PurchaseOrder order = create(supplierId, note, lines);
        order.markRetail();
        order.send();
        List<ReceiptInput> everything = order.getLines().stream()
                .map(l -> new ReceiptInput(l.getId(), l.getOrderedQty(), null, null, null, null, null))
                .toList();
        receiving.receive(order, everything, note);
        return order;
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

    /** Receives a delivery: the goods receipt records it, and the order's lines and status move on. */
    public PurchaseOrder receive(UUID id, List<ReceiptInput> receipts, String note) {
        PurchaseOrder order = get(id);
        requireActiveSupplier(order.getSupplierId());
        receiving.receive(order, receipts, note);
        return order;
    }

    /** Receives a supplier's replacement for lost units against the order's receipts. */
    public PurchaseOrder compensate(UUID id, List<CompensationInput> lines, String note) {
        PurchaseOrder order = get(id);
        receiving.compensate(order, lines, note);
        return order;
    }

    /** The goods receipts of each order, oldest first; orders with none are absent from the map. */
    @Transactional(readOnly = true)
    public Map<UUID, List<GoodsReceipt>> receiptsOf(List<UUID> orderIds) {
        if (orderIds.isEmpty()) {
            return Map.of();
        }
        return receiptRepository.findByPurchaseOrderIdInOrderByCreatedAtAsc(orderIds).stream()
                .collect(Collectors.groupingBy(GoodsReceipt::getPurchaseOrderId, LinkedHashMap::new, Collectors.toList()));
    }

    /** The order a goods receipt belongs to, so a stock movement can link to it; scoped to the active branch. */
    @Transactional(readOnly = true)
    public PurchaseOrder getByReceipt(UUID receiptId) {
        GoodsReceipt receipt = receiptRepository.findByIdAndBranchId(receiptId, BranchContext.require())
                .orElseThrow(() -> ResourceNotFoundException.of("GoodsReceipt", receiptId));
        return get(receipt.getPurchaseOrderId());
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
