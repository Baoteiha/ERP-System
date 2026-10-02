package vn.essvn.erpcafe.sales.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
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
import vn.essvn.erpcafe.catalog.api.PricedLine;
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

    /** When the sale completed; null on legacy PAID rows, which reports count at creation. */
    @Column(name = "completed_at")
    private Instant completedAt;

    /** When a void or refund took the sale back; null for an order that was never reversed. */
    @Column(name = "reversed_at")
    private Instant reversedAt;

    /** The till's id for this create, unique per branch, so a retried request finds this order instead of making another. */
    @Column(name = "client_request_id")
    private UUID clientRequestId;

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @JoinColumn(name = "order_id", nullable = false)
    private List<OrderLine> lines = new ArrayList<>();

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @JoinColumn(name = "order_id", nullable = false)
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

    /**
     * Adds a priced line, recording the product's recipe version in force (null: no recipe); its
     * discount may not exceed its gross. Totals are recomputed.
     */
    public void addLine(PricedLine priced, int quantity, BigDecimal lineDiscount, UUID recipeVersionId) {
        requireOpen("add lines to");
        if (quantity <= 0) {
            throw new BusinessRuleException("Line quantity must be positive");
        }
        Money discount = Money.of(lineDiscount == null ? BigDecimal.ZERO : lineDiscount);
        Money gross = OrderLine.grossTotal(priced, quantity);
        if (discount.getAmount().signum() < 0 || discount.getAmount().compareTo(gross.getAmount()) > 0) {
            throw new BusinessRuleException("Line discount must be between 0 and the line total of %s"
                    .formatted(gross.getAmount().toPlainString()));
        }
        lines.add(new OrderLine(priced, quantity, discount, recipeVersionId));
        recalculate();
    }

    /** Sets the whole-order discount; it may not exceed the subtotal left after line discounts. */
    public void applyOrderDiscount(BigDecimal discount) {
        requireOpen("discount");
        BigDecimal value = Money.of(discount == null ? BigDecimal.ZERO : discount).getAmount();
        BigDecimal room = subtotal.subtract(lineDiscounts());
        if (value.signum() < 0 || value.compareTo(room) > 0) {
            throw new BusinessRuleException("Order discount must be between 0 and %s"
                    .formatted(room.toPlainString()));
        }
        this.orderDiscount = value;
        recalculate();
    }

    public void addPayment(Payment payment) {
        requireOpen("take payment on");
        if (payment.getAmount().getAmount().compareTo(balanceDue().getAmount()) > 0) {
            throw new BusinessRuleException("Payment exceeds the balance due of %s"
                    .formatted(balanceDue().getAmount().toPlainString()));
        }
        payments.add(payment);
    }

    public Money balanceDue() {
        return grandTotalMoney().subtract(amountPaid());
    }

    public void markPaidIfSettled() {
        if (status == OrderStatus.OPEN && isFullyPaid()) {
            status = OrderStatus.PAID;
        }
    }

    /** Guards the completion side effects: call before touching stock. */
    public void requireCompletable() {
        requireStatus(OrderStatus.PAID, "completed");
    }

    public void complete(BigDecimal cogs) {
        requireCompletable();
        this.cogsTotal = cogs;
        this.status = OrderStatus.COMPLETED;
        this.completedAt = Instant.now();
    }

    /** Cancels an open order, paying every tender back in the method it came in. */
    public void cancel() {
        requireOpen("cancel");
        refundEachTender();
        this.status = OrderStatus.CANCELLED;
    }

    /** Reverses a paid/completed order as a mistake. Returns true if stock was already deducted. */
    public boolean voidOrder() {
        if (status != OrderStatus.PAID && status != OrderStatus.COMPLETED) {
            throw new BusinessRuleException("Only PAID or COMPLETED orders can be voided (was %s)".formatted(status));
        }
        boolean wasCompleted = status == OrderStatus.COMPLETED;
        this.status = OrderStatus.VOID;
        this.reversedAt = Instant.now();
        refundEachTender();
        return wasCompleted;
    }

    /** Refunds a paid/completed order. Returns true if stock was already deducted. */
    public boolean refund() {
        if (status != OrderStatus.PAID && status != OrderStatus.COMPLETED) {
            throw new BusinessRuleException("Only PAID or COMPLETED orders can be refunded (was %s)".formatted(status));
        }
        boolean wasCompleted = status == OrderStatus.COMPLETED;
        this.status = OrderStatus.REFUNDED;
        this.reversedAt = Instant.now();
        refundEachTender();
        return wasCompleted;
    }

    /** Pays every tender back in the method and amount it came in. */
    private void refundEachTender() {
        for (Payment p : List.copyOf(payments)) {
            if (p.getType() == PaymentType.PAYMENT) {
                recordRefund(p.getMethod(), p.getAmount());
            }
        }
    }

    private void recordRefund(PaymentMethod method, Money amount) {
        payments.add(new Payment(method, PaymentType.REFUND, amount));
    }

    private void requireOpen(String action) {
        requireStatus(OrderStatus.OPEN, action);
    }

    private void requireStatus(OrderStatus expected, String action) {
        if (status != expected) {
            throw new BusinessRuleException("Order must be %s to %s (was %s)".formatted(expected, action, status));
        }
    }

    // --- totals: gross = subtotal; net = gross - discounts; tax on net; only tax needs rounding, done here; the rest is normalised by Money ---

    private BigDecimal lineDiscounts() {
        return lines.stream().map(l -> l.getLineDiscount().getAmount()).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private void recalculate() {
        this.subtotal = lines.stream().map(l -> l.gross().getAmount()).reduce(BigDecimal.ZERO, BigDecimal::add);
        this.discountTotal = lineDiscounts().add(orderDiscount);
        BigDecimal net = subtotal.subtract(discountTotal);
        this.taxTotal = net.multiply(taxRate).setScale(Money.SCALE, Money.ROUNDING);
        this.grandTotal = net.add(taxTotal);
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

    public Instant getCompletedAt() {
        return completedAt;
    }

    public UUID getClientRequestId() {
        return clientRequestId;
    }

    public void setClientRequestId(UUID clientRequestId) {
        this.clientRequestId = clientRequestId;
    }

    public Instant getReversedAt() {
        return reversedAt;
    }

    public List<OrderLine> getLines() {
        return Collections.unmodifiableList(lines);
    }

    public List<Payment> getPayments() {
        return Collections.unmodifiableList(payments);
    }
}
