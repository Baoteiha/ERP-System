package vn.essvn.erpcafe.sales.domain;

import java.math.BigDecimal;
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
import vn.essvn.erpcafe.common.domain.Money;
import vn.essvn.erpcafe.common.exception.BusinessRuleException;

/**
 * A customer order (a POS ticket) at a branch. Aggregate root over its lines and
 * payments. Totals are single-currency BigDecimals; the state machine enforces
 * valid transitions and binds stock effects to specific transitions.
 */
@Entity
@Table(name = "sales_order")
public class Order extends BranchScopedEntity {

    @Column(name = "cashier_user_id")
    private UUID cashierUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "order_type", nullable = false, length = 16)
    private OrderType orderType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private OrderStatus status = OrderStatus.OPEN;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency = Money.DEFAULT_CURRENCY;

    @Column(name = "tax_rate", nullable = false, precision = 9, scale = 6)
    private BigDecimal taxRate = BigDecimal.ZERO;

    @Column(name = "subtotal", nullable = false, precision = 19, scale = 4)
    private BigDecimal subtotal = BigDecimal.ZERO;

    @Column(name = "order_discount", nullable = false, precision = 19, scale = 4)
    private BigDecimal orderDiscount = BigDecimal.ZERO;

    @Column(name = "discount_total", nullable = false, precision = 19, scale = 4)
    private BigDecimal discountTotal = BigDecimal.ZERO;

    @Column(name = "tax_total", nullable = false, precision = 19, scale = 4)
    private BigDecimal taxTotal = BigDecimal.ZERO;

    @Column(name = "grand_total", nullable = false, precision = 19, scale = 4)
    private BigDecimal grandTotal = BigDecimal.ZERO;

    @Column(name = "cogs_total", precision = 19, scale = 4)
    private BigDecimal cogsTotal;

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @JoinColumn(name = "order_id")
    private List<OrderLine> lines = new ArrayList<>();

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @JoinColumn(name = "order_id")
    private List<Payment> payments = new ArrayList<>();

    protected Order() {
    }

    public Order(UUID branchId, UUID cashierUserId, OrderType orderType, BigDecimal taxRate) {
        setBranchId(branchId);
        this.cashierUserId = cashierUserId;
        this.orderType = orderType;
        this.taxRate = taxRate == null ? BigDecimal.ZERO : taxRate;
    }

    // --- money helpers ---

    public Money grandTotalMoney() {
        return Money.of(grandTotal, currency);
    }

    /** Net paid = Σ PAYMENT − Σ REFUND. */
    public Money amountPaid() {
        BigDecimal net = BigDecimal.ZERO;
        for (Payment p : payments) {
            net = p.getType() == PaymentType.REFUND
                    ? net.subtract(p.getAmount().getAmount())
                    : net.add(p.getAmount().getAmount());
        }
        return Money.of(net, currency);
    }

    public boolean isFullyPaid() {
        return amountPaid().getAmount().compareTo(grandTotal) >= 0;
    }

    // --- state transitions (side effects live in the service; this enforces validity) ---

    public void addLine(OrderLine line) {
        requireOpen("add lines to");
        lines.add(line);
    }

    public void addPayment(Payment payment) {
        if (status != OrderStatus.OPEN && status != OrderStatus.PAID) {
            throw new BusinessRuleException("Cannot take payment on a %s order".formatted(status));
        }
        payments.add(payment);
    }

    /** Records a refund tender (money out). Used during void/refund; not status-guarded. */
    public void recordRefund(PaymentMethod method, Money amount) {
        payments.add(new Payment(method, PaymentType.REFUND, amount));
    }

    public void markPaidIfSettled() {
        if (status == OrderStatus.OPEN && isFullyPaid()) {
            status = OrderStatus.PAID;
        }
    }

    public void complete(BigDecimal cogs) {
        requireStatus(OrderStatus.PAID, "completed");
        this.cogsTotal = cogs;
        this.status = OrderStatus.COMPLETED;
    }

    public void cancel() {
        requireOpen("cancel");
        if (!payments.isEmpty()) {
            throw new BusinessRuleException("Order has payments; refund instead of cancel");
        }
        this.status = OrderStatus.CANCELLED;
    }

    /** Reverses a paid/completed order as a mistake. Returns true if stock was already deducted. */
    public boolean voidOrder() {
        if (status != OrderStatus.PAID && status != OrderStatus.COMPLETED) {
            throw new BusinessRuleException("Only PAID or COMPLETED orders can be voided (was %s)".formatted(status));
        }
        boolean wasCompleted = status == OrderStatus.COMPLETED;
        this.status = OrderStatus.VOID;
        return wasCompleted;
    }

    /** Refunds a paid/completed order. Returns true if stock was already deducted. */
    public boolean refund() {
        if (status != OrderStatus.PAID && status != OrderStatus.COMPLETED) {
            throw new BusinessRuleException("Only PAID or COMPLETED orders can be refunded (was %s)".formatted(status));
        }
        boolean wasCompleted = status == OrderStatus.COMPLETED;
        this.status = OrderStatus.REFUNDED;
        return wasCompleted;
    }

    private void requireOpen(String action) {
        requireStatus(OrderStatus.OPEN, action);
    }

    private void requireStatus(OrderStatus expected, String action) {
        if (status != expected) {
            throw new BusinessRuleException("Order must be %s to %s (was %s)".formatted(expected, action, status));
        }
    }

    // --- totals (computed and set by the pricing service) ---

    public void setTotals(BigDecimal subtotal, BigDecimal orderDiscount, BigDecimal discountTotal,
            BigDecimal taxTotal, BigDecimal grandTotal) {
        this.subtotal = subtotal;
        this.orderDiscount = orderDiscount;
        this.discountTotal = discountTotal;
        this.taxTotal = taxTotal;
        this.grandTotal = grandTotal;
    }

    public UUID getCashierUserId() {
        return cashierUserId;
    }

    public OrderType getOrderType() {
        return orderType;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public String getCurrency() {
        return currency;
    }

    public BigDecimal getTaxRate() {
        return taxRate;
    }

    public BigDecimal getSubtotal() {
        return subtotal;
    }

    public BigDecimal getOrderDiscount() {
        return orderDiscount;
    }

    public BigDecimal getDiscountTotal() {
        return discountTotal;
    }

    public BigDecimal getTaxTotal() {
        return taxTotal;
    }

    public BigDecimal getGrandTotal() {
        return grandTotal;
    }

    public BigDecimal getCogsTotal() {
        return cogsTotal;
    }

    public List<OrderLine> getLines() {
        return lines;
    }

    public List<Payment> getPayments() {
        return payments;
    }
}
