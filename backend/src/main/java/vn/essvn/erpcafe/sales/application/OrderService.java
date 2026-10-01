package vn.essvn.erpcafe.sales.application;

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
import vn.essvn.erpcafe.sales.domain.OrderStatus;
import vn.essvn.erpcafe.sales.domain.OrderLine;
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
        return orderRepository.findByIdAndBranchId(id, activeBranch())
                .orElseThrow(() -> ResourceNotFoundException.of("Order", id));
    }

    public Order create(CreateOrderInput input) {
        UUID branchId = activeBranch();
        UUID cashierId = identityApi.currentUserId().orElse(null);
        Order order = new Order(branchId, cashierId, input.orderType(), input.taxRate());

        for (LineInput li : input.lines()) {
            catalogApi.validateModifierSelection(li.productId(), li.modifierIds());
            PricedLine priced = catalogApi.priceLine(li.productId(), branchId, li.modifierIds());
            order.addLine(priced, li.quantity(), li.lineDiscount());
        }
        order.applyOrderDiscount(input.orderDiscount());
        Order saved = orderRepository.save(order);
        saved.markPaidIfSettled(); // a zero-total ticket has nothing to collect
        return finishIfPaid(saved);
    }

    public Order addPayment(UUID orderId, PaymentInput input) {
        Order order = get(orderId);
        order.addPayment(new Payment(input.method(), PaymentType.PAYMENT, Money.of(input.amount())));
        order.markPaidIfSettled();
        return finishIfPaid(order);
    }

    /** Finalises a paid order (legacy rows only; full payment completes on its own). */
    public Order complete(UUID orderId) {
        return finish(get(orderId));
    }

    /** Full payment completes the sale in the same transaction: stock, COGS, status. */
    private Order finishIfPaid(Order order) {
        return order.getStatus() == OrderStatus.PAID ? finish(order) : order;
    }

    private Order finish(Order order) {
        order.requireCompletable(); // before any stock movement
        CogsResult cogs = inventoryApi.deductForOrder(order.getBranchId(), stockLinesFor(order), REF_ORDER, order.getId());
        order.complete(cogs.totalCost().getAmount());
        return order;
    }

    public Order cancel(UUID orderId) {
        Order order = get(orderId);
        order.cancel();
        return order;
    }

    public Order voidOrder(UUID orderId, PaymentMethod refundMethod) {
        Order order = get(orderId);
        boolean stockWasDeducted = order.voidOrder(refundMethod);
        if (stockWasDeducted) {
            inventoryApi.returnForOrder(order.getBranchId(), stockLinesFor(order), REF_VOID, orderId);
        }
        return order;
    }

    public Order refund(UUID orderId, PaymentMethod refundMethod) {
        Order order = get(orderId);
        boolean stockWasDeducted = order.refund(refundMethod);
        if (stockWasDeducted) {
            inventoryApi.returnForOrder(order.getBranchId(), stockLinesFor(order), REF_REFUND, orderId);
        }
        return order;
    }

    // --- helpers ---

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

    private UUID activeBranch() {
        return BranchContext.current()
                .orElseThrow(() -> new BusinessRuleException("Active branch required (set X-Branch-Id header)"));
    }
}
