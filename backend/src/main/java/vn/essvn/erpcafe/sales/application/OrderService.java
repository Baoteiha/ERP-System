package vn.essvn.erpcafe.sales.application;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

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
import vn.essvn.erpcafe.organization.api.BranchDto;
import vn.essvn.erpcafe.organization.api.OrganizationApi;
import vn.essvn.erpcafe.sales.application.SalesInputs.CreateOrderInput;
import vn.essvn.erpcafe.sales.application.SalesInputs.LineInput;
import vn.essvn.erpcafe.sales.application.SalesInputs.PaymentInput;
import vn.essvn.erpcafe.sales.application.SalesInputs.PlacedOrder;
import vn.essvn.erpcafe.sales.domain.Order;
import vn.essvn.erpcafe.sales.domain.OrderStatus;
import vn.essvn.erpcafe.sales.domain.OrderLine;
import vn.essvn.erpcafe.sales.domain.Payment;
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
    private final OrganizationApi organizationApi;
    private final TransactionTemplate transactions;

    public OrderService(OrderRepository orderRepository, CatalogApi catalogApi,
            InventoryApi inventoryApi, IdentityApi identityApi,
            OrganizationApi organizationApi, PlatformTransactionManager transactionManager) {
        this.orderRepository = orderRepository;
        this.catalogApi = catalogApi;
        this.inventoryApi = inventoryApi;
        this.identityApi = identityApi;
        this.organizationApi = organizationApi;
        this.transactions = new TransactionTemplate(transactionManager);
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

    /**
     * Creates the order, or returns the one this {@code clientRequestId} already made at the branch.
     * Runs outside any caller transaction: the loser of two simultaneous creates hits the unique index, its
     * transaction is rolled back whole (stock included), and it then reads the winner's committed order.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public PlacedOrder place(CreateOrderInput input) {
        UUID requestId = input.clientRequestId();
        if (requestId == null) {
            return new PlacedOrder(inTransaction(() -> create(input)), true);
        }
        UUID branchId = activeBranch();
        Order existing = inTransaction(() -> orderRepository.findByBranchIdAndClientRequestId(branchId, requestId)
                .orElse(null));
        if (existing != null) {
            return new PlacedOrder(existing, false);
        }
        try {
            return new PlacedOrder(inTransaction(() -> create(input)), true);
        } catch (DataIntegrityViolationException raced) {
            Order winner = inTransaction(() -> orderRepository.findByBranchIdAndClientRequestId(branchId, requestId)
                    .orElse(null));
            if (winner == null) {
                throw raced; // not our index: a genuine integrity failure
            }
            return new PlacedOrder(winner, false);
        }
    }

    private <T> T inTransaction(Supplier<T> work) {
        return transactions.execute(status -> work.get());
    }

    Order create(CreateOrderInput input) {
        Order built = build(input);
        built.setClientRequestId(input.clientRequestId());
        // flush now so a duplicate request id fails before any stock moves
        Order saved = orderRepository.saveAndFlush(built);
        saved.markPaidIfSettled(); // a zero-total ticket has nothing to collect
        return finishIfPaid(saved);
    }

    /** Prices a ticket exactly as {@link #create} would, without saving it: the till's live totals. */
    @Transactional(readOnly = true)
    public Order quote(CreateOrderInput input) {
        return build(input);
    }

    private Order build(CreateOrderInput input) {
        UUID branchId = activeBranch();
        UUID cashierId = identityApi.currentUserId().orElse(null);
        // the rate is the branch's setting, never the client's: a till cannot undercharge tax
        BranchDto branch = organizationApi.findBranch(branchId)
                .orElseThrow(() -> ResourceNotFoundException.of("Branch", branchId));
        Order order = new Order(branchId, cashierId, input.orderType(), branch.taxRate());

        for (LineInput li : input.lines()) {
            PricedLine priced = catalogApi.priceLine(branchId, li.productId(), li.modifierIds());
            order.addLine(priced, li.quantity(), li.lineDiscount());
        }
        order.applyOrderDiscount(input.orderDiscount());
        return order;
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

    public Order voidOrder(UUID orderId) {
        Order order = get(orderId);
        boolean stockWasDeducted = order.voidOrder();
        if (stockWasDeducted) {
            inventoryApi.reverseDeduction(order.getBranchId(), REF_ORDER, orderId, REF_VOID);
        }
        return order;
    }

    public Order refund(UUID orderId) {
        Order order = get(orderId);
        boolean stockWasDeducted = order.refund();
        if (stockWasDeducted) {
            inventoryApi.reverseDeduction(order.getBranchId(), REF_ORDER, orderId, REF_REFUND);
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
