package vn.essvn.erpcafe.sales.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.essvn.erpcafe.catalog.api.CatalogApi;
import vn.essvn.erpcafe.catalog.api.IngredientConsumption;
import vn.essvn.erpcafe.catalog.api.PricedLine;
import vn.essvn.erpcafe.common.context.BranchContext;
import vn.essvn.erpcafe.common.domain.Money;
import vn.essvn.erpcafe.common.exception.BusinessRuleException;
import vn.essvn.erpcafe.common.exception.ResourceNotFoundException;
import vn.essvn.erpcafe.identity.api.IdentityApi;
import vn.essvn.erpcafe.inventory.api.CogsResult;
import vn.essvn.erpcafe.inventory.api.InventoryApi;
import vn.essvn.erpcafe.inventory.api.StockLine;
import vn.essvn.erpcafe.sales.application.SalesInputs.CreateOrderInput;
import vn.essvn.erpcafe.sales.application.SalesInputs.LineInput;
import vn.essvn.erpcafe.sales.application.SalesInputs.PaymentInput;
import vn.essvn.erpcafe.sales.domain.Order;
import vn.essvn.erpcafe.sales.domain.OrderLine;
import vn.essvn.erpcafe.sales.domain.OrderLineModifier;
import vn.essvn.erpcafe.sales.domain.Payment;
import vn.essvn.erpcafe.sales.domain.PaymentMethod;
import vn.essvn.erpcafe.sales.domain.PaymentType;
import vn.essvn.erpcafe.sales.persistence.OrderRepository;

/**
 * The POS use cases. Orders are priced via {@link CatalogApi}; on completion,
 * recipes are exploded and stock is deducted (COGS captured) via {@link InventoryApi};
 * void/refund return the stock. All operations act on the active branch.
 */
@Service
@Transactional
public class OrderService {

    private static final int MONEY_SCALE = 4;
    private static final String REF_ORDER = "ORDER";
    private static final String REF_VOID = "ORDER_VOID";
    private static final String REF_REFUND = "ORDER_REFUND";

    private final OrderRepository orderRepository;
    private final CatalogApi catalogApi;
    private final InventoryApi inventoryApi;
    private final IdentityApi identityApi;

    public OrderService(OrderRepository orderRepository, CatalogApi catalogApi,
            InventoryApi inventoryApi, IdentityApi identityApi) {
        this.orderRepository = orderRepository;
        this.catalogApi = catalogApi;
        this.inventoryApi = inventoryApi;
        this.identityApi = identityApi;
    }

    @Transactional(readOnly = true)
    public Page<Order> list(Pageable pageable) {
        return orderRepository.findByBranchIdOrderByCreatedAtDesc(activeBranch(), pageable);
    }

    @Transactional(readOnly = true)
    public Order get(UUID id) {
        return orderRepository.findById(id).orElseThrow(() -> ResourceNotFoundException.of("Order", id));
    }

    public Order create(CreateOrderInput input) {
        UUID branchId = activeBranch();
        UUID cashierId = identityApi.currentUserId().orElse(null);
        Order order = new Order(branchId, cashierId, input.orderType(), input.taxRate());

        for (LineInput li : input.lines()) {
            if (li.quantity() <= 0) {
                throw new BusinessRuleException("Line quantity must be positive");
            }
            catalogApi.validateModifierSelection(li.productId(), li.modifierIds());
            PricedLine priced = catalogApi.priceLine(li.productId(), branchId, li.modifierIds());

            Money lineDiscount = Money.of(li.lineDiscount() == null ? BigDecimal.ZERO : li.lineDiscount());
            OrderLine line = new OrderLine(li.productId(), priced.productName(),
                    priced.unitBasePrice(), li.quantity(), lineDiscount);
            priced.modifiers().forEach(m ->
                    line.addModifier(new OrderLineModifier(m.modifierId(), m.name(), m.priceDelta())));

            // lineTotal = unitTotal (base + modifiers) × qty − lineDiscount
            Money lineTotal = priced.unitTotal()
                    .multiply(BigDecimal.valueOf(li.quantity()))
                    .subtract(lineDiscount);
            line.setLineTotal(lineTotal);
            order.addLine(line);
        }

        applyTotals(order, input.orderDiscount());
        return orderRepository.save(order);
    }

    public Order addPayment(UUID orderId, PaymentInput input) {
        Order order = get(orderId);
        order.addPayment(new Payment(input.method(), PaymentType.PAYMENT, Money.of(input.amount())));
        order.markPaidIfSettled();
        return order;
    }

    /** Finalises a paid order: deducts ingredient stock and captures COGS. */
    public Order complete(UUID orderId) {
        Order order = get(orderId);
        CogsResult cogs = inventoryApi.deductForOrder(order.getBranchId(), stockLinesFor(order), REF_ORDER, orderId);
        order.complete(cogs.totalCost().getAmount()); // requires PAID
        return order;
    }

    public Order cancel(UUID orderId) {
        Order order = get(orderId);
        order.cancel();
        return order;
    }

    public Order voidOrder(UUID orderId, PaymentMethod refundMethod) {
        Order order = get(orderId);
        Money paid = order.amountPaid();
        boolean stockWasDeducted = order.voidOrder();
        if (stockWasDeducted) {
            inventoryApi.returnForOrder(order.getBranchId(), stockLinesFor(order), REF_VOID, orderId);
        }
        order.recordRefund(refundMethod, paid);
        return order;
    }

    public Order refund(UUID orderId, PaymentMethod refundMethod) {
        Order order = get(orderId);
        Money paid = order.amountPaid();
        boolean stockWasDeducted = order.refund();
        if (stockWasDeducted) {
            inventoryApi.returnForOrder(order.getBranchId(), stockLinesFor(order), REF_REFUND, orderId);
        }
        order.recordRefund(refundMethod, paid);
        return order;
    }

    // --- helpers ---

    private void applyTotals(Order order, BigDecimal orderDiscountInput) {
        BigDecimal subtotal = order.getLines().stream()
                .map(l -> l.getLineTotal().getAmount())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal orderDiscount = orderDiscountInput == null ? BigDecimal.ZERO : orderDiscountInput;
        BigDecimal lineDiscounts = order.getLines().stream()
                .map(l -> l.getLineDiscount().getAmount())
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal taxableBase = subtotal.subtract(orderDiscount).max(BigDecimal.ZERO);
        BigDecimal taxTotal = scale(taxableBase.multiply(order.getTaxRate()));
        BigDecimal grandTotal = scale(taxableBase.add(taxTotal));
        BigDecimal discountTotal = scale(lineDiscounts.add(orderDiscount));

        order.setTotals(scale(subtotal), scale(orderDiscount), discountTotal, taxTotal, grandTotal);
    }

    /** Explodes every line (product + modifiers × quantity) into ingredient stock lines. */
    private List<StockLine> stockLinesFor(Order order) {
        List<StockLine> lines = new ArrayList<>();
        for (OrderLine line : order.getLines()) {
            List<IngredientConsumption> consumption =
                    catalogApi.explode(line.getProductId(), line.getQuantity(), line.modifierIds());
            consumption.forEach(c -> lines.add(new StockLine(c.ingredientId(), c.quantity(), c.unit())));
        }
        return lines;
    }

    private static BigDecimal scale(BigDecimal value) {
        return value.setScale(MONEY_SCALE, RoundingMode.HALF_EVEN);
    }

    private UUID activeBranch() {
        return BranchContext.current()
                .orElseThrow(() -> new BusinessRuleException("Active branch required (set X-Branch-Id header)"));
    }
}
