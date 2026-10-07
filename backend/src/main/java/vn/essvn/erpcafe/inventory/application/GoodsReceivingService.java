package vn.essvn.erpcafe.inventory.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.essvn.erpcafe.common.domain.Money;
import vn.essvn.erpcafe.common.domain.Unit;
import vn.essvn.erpcafe.common.exception.BusinessRuleException;
import vn.essvn.erpcafe.common.exception.ResourceNotFoundException;
import vn.essvn.erpcafe.inventory.application.PurchaseInputs.CompensationInput;
import vn.essvn.erpcafe.inventory.application.PurchaseInputs.ReceiptInput;
import vn.essvn.erpcafe.inventory.domain.GoodsReceipt;
import vn.essvn.erpcafe.inventory.domain.GoodsReceiptKind;
import vn.essvn.erpcafe.inventory.domain.GoodsReceiptLine;
import vn.essvn.erpcafe.inventory.domain.Ingredient;
import vn.essvn.erpcafe.inventory.domain.PurchaseOrder;
import vn.essvn.erpcafe.inventory.domain.PurchaseOrderLine;
import vn.essvn.erpcafe.inventory.domain.PurchaseOrderStatus;
import vn.essvn.erpcafe.inventory.domain.ReceiptSplit;
import vn.essvn.erpcafe.inventory.persistence.GoodsReceiptRepository;
import vn.essvn.erpcafe.inventory.persistence.IngredientRepository;

/**
 * Class: GoodsReceivingService
 * Purpose: Turns a delivery or a replacement into a goods receipt, the purchase lines' progress, and stock at the price paid.
 */
@Service
@Transactional
public class GoodsReceivingService {

    public static final String REF_TYPE = "GOODS_RECEIPT";

    private final GoodsReceiptRepository receiptRepository;
    private final IngredientRepository ingredientRepository;
    private final StockLedgerService ledger;

    public GoodsReceivingService(GoodsReceiptRepository receiptRepository, IngredientRepository ingredientRepository,
            StockLedgerService ledger) {
        this.receiptRepository = receiptRepository;
        this.ingredientRepository = ingredientRepository;
        this.ledger = ledger;
    }

    /** Receives replacements for units a delivery lost, at the price those units cost; the loss shrinks by as much. */
    public GoodsReceipt compensate(PurchaseOrder order, List<CompensationInput> inputs, String note) {
        if (order.getStatus() == PurchaseOrderStatus.CANCELLED) {
            throw new BusinessRuleException("Cannot receive replacements on a cancelled order");
        }
        if (inputs == null || inputs.isEmpty()) {
            throw new BusinessRuleException("A replacement needs at least one line");
        }
        Map<UUID, GoodsReceiptLine> received = receiptRepository.findByPurchaseOrderIdOrderByCreatedAtAsc(order.getId())
                .stream().flatMap(r -> r.getLines().stream())
                .collect(Collectors.toMap(GoodsReceiptLine::getId, Function.identity()));
        /**
         * Class: Resolved
         * Purpose: A replacement input paired with the lost receipt line and purchase line it answers.
         */
        record Resolved(CompensationInput input, GoodsReceiptLine lost, PurchaseOrderLine line) {
        }
        List<Resolved> resolved = new ArrayList<>();
        for (CompensationInput input : inputs) {
            GoodsReceiptLine lost = received.get(input.receiptLineId());
            if (lost == null) {
                throw ResourceNotFoundException.of("GoodsReceiptLine", input.receiptLineId());
            }
            resolved.add(new Resolved(input, lost, lineOf(order, lost.getPurchaseOrderLineId())));
        }
        resolved.sort(Comparator.comparing(r -> r.line().getIngredientId()));
        GoodsReceipt receipt = receiptRepository.save(
                new GoodsReceipt(order.getId(), order.getBranchId(), GoodsReceiptKind.COMPENSATION, note));
        for (Resolved r : resolved) {
            requireActive(requireIngredient(r.line().getIngredientId()));
            r.lost().compensate(r.input().quantity());
            Unit lineUnit = Unit.parse(r.line().getUnit());
            BigDecimal rate = ledger.toBaseRate(order.getBranchId(), r.line().getIngredientId(),
                    r.lost().getCostPerUnit(), lineUnit.symbol());
            ledger.receive(order.getBranchId(), r.line().getIngredientId(), r.input().quantity(), lineUnit.symbol(),
                    Money.of(rate), REF_TYPE, receipt.getId());
            receipt.addLine(GoodsReceiptLine.replacement(r.lost(), r.input().quantity(), null));
        }
        return receipt;
    }

    /** Receives a delivery against a sent order: each line's split decides the order's progress, stock and loss. */
    public GoodsReceipt receive(PurchaseOrder order, List<ReceiptInput> inputs, String note) {
        if (order.getStatus() != PurchaseOrderStatus.SENT
                && order.getStatus() != PurchaseOrderStatus.PARTIALLY_RECEIVED) {
            throw new BusinessRuleException("Order must be SENT to receive (was %s)".formatted(order.getStatus()));
        }
        if (inputs == null || inputs.isEmpty()) {
            throw new BusinessRuleException("A receipt needs at least one line");
        }
        GoodsReceipt receipt = receiptRepository.save(
                new GoodsReceipt(order.getId(), order.getBranchId(), GoodsReceiptKind.DELIVERY, note));
        // Process in ingredientId order: stock rows must be locked in one global order on every path, or a
        // receipt and an order completion touching the same two ingredients can deadlock.
        /**
         * Class: Resolved
         * Purpose: Pairs a receipt input with the purchase line it names.
         */
        record Resolved(ReceiptInput input, PurchaseOrderLine line) {
        }
        List<Resolved> resolved = new ArrayList<>();
        for (ReceiptInput input : inputs) {
            resolved.add(new Resolved(input, lineOf(order, input.lineId())));
        }
        resolved.sort(Comparator.comparing(r -> r.line().getIngredientId()));
        for (Resolved r : resolved) {
            receipt.addLine(receiveLine(order.getBranchId(), receipt.getId(), r.line(), r.input()));
        }
        order.refreshReceiptStatus();
        return receipt;
    }

    private GoodsReceiptLine receiveLine(UUID branchId, UUID receiptId, PurchaseOrderLine line, ReceiptInput input) {
        requireActive(requireIngredient(line.getIngredientId()));
        BigDecimal delivered = input.receivedQty();
        if (delivered == null || delivered.signum() <= 0) {
            throw new BusinessRuleException("Received quantity must be positive");
        }
        Unit lineUnit = Unit.parse(line.getUnit());
        Unit countedIn = lineUnit;
        BigDecimal deliveredOrdering;
        if (line.hasPack()) {
            if (input.unit() != null && !input.unit().isBlank()) {
                throw new BusinessRuleException("A %s line is received in whole %s; leave the unit out"
                        .formatted(line.getPackLabel(), line.getPackLabel()));
            }
            PurchaseOrderService.requireWholePacks(delivered, line.getPackLabel());
            deliveredOrdering = delivered;
        } else {
            // Without a pack a delivery may be counted in any unit of the line's dimension (ADR-0006).
            countedIn = input.unit() == null || input.unit().isBlank() ? lineUnit : Unit.parse(input.unit());
            deliveredOrdering = Unit.convert(delivered, countedIn, lineUnit);
        }
        BigDecimal damaged = input.damagedQty() == null ? BigDecimal.ZERO
                : Unit.convert(input.damagedQty(), countedIn, lineUnit);
        ReceiptSplit split = ReceiptSplit.of(deliveredOrdering, line.outstandingQty(), line.unitsPerOrderingUnit(),
                input.overDeliveryKind(), damaged);

        // The price per ordering unit is the order's, or the invoice's when it differs (per pack, or per the
        // unit the delivery was counted in); stock and the loss then use it per unit of the line.
        BigDecimal costPerOrdering = input.unitCostOverride() == null ? line.getUnitCost().getAmount()
                : line.hasPack() ? input.unitCostOverride()
                : Unit.convertRate(input.unitCostOverride(), countedIn, lineUnit);
        BigDecimal costPerUnit = costPerOrdering.divide(line.unitsPerOrderingUnit(), Unit.CALC_SCALE,
                RoundingMode.HALF_EVEN);

        line.growOrdered(split.growOrderedBy());
        line.receive(split.countedQty());
        // Damaged units never enter stock: the loss is valued at the price paid, not the blended average.
        BigDecimal rate = ledger.toBaseRate(branchId, line.getIngredientId(), costPerUnit, lineUnit.symbol());
        if (split.chargedIntoStock().signum() > 0) {
            ledger.receive(branchId, line.getIngredientId(), split.chargedIntoStock(), lineUnit.symbol(),
                    Money.of(rate), REF_TYPE, receiptId);
        }
        if (split.freeIntoStock().signum() > 0) {
            ledger.receive(branchId, line.getIngredientId(), split.freeIntoStock(), lineUnit.symbol(),
                    Money.zero(), REF_TYPE, receiptId);
        }
        return GoodsReceiptLine.delivered(line.getId(), deliveredOrdering, damaged, split, input.overDeliveryKind(),
                Money.of(costPerOrdering, line.getUnitCost().getCurrency()),
                costPerUnit.setScale(Money.SCALE, RoundingMode.HALF_EVEN), input.note());
    }

    static PurchaseOrderLine lineOf(PurchaseOrder order, UUID lineId) {
        return order.getLines().stream()
                .filter(l -> l.getId().equals(lineId))
                .findFirst()
                .orElseThrow(() -> ResourceNotFoundException.of("PurchaseOrderLine", lineId));
    }

    Ingredient requireIngredient(UUID ingredientId) {
        return ingredientRepository.findById(ingredientId)
                .orElseThrow(() -> ResourceNotFoundException.of("Ingredient", ingredientId));
    }

    static void requireActive(Ingredient ingredient) {
        if (!ingredient.isActive()) {
            throw new BusinessRuleException("Ingredient " + ingredient.getName() + " is inactive");
        }
    }
}
