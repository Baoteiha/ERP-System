package vn.essvn.erpcafe.sales.domain;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import vn.essvn.erpcafe.common.domain.AuditableEntity;
import vn.essvn.erpcafe.common.domain.Money;

/**
 * A tender (PAYMENT) or refund (REFUND) against an order. Immutable financial
 * record; the audit columns capture when and by whom. The FK to the order is
 * managed by {@code Order.payments}.
 */
@Entity
@Table(name = "payment")
public class Payment extends AuditableEntity {

    @Enumerated(EnumType.STRING)
    @Column(name = "method", nullable = false, length = 16)
    private PaymentMethod method;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 16)
    private PaymentType type;

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "amount", column = @Column(name = "amount", precision = 19, scale = 4)),
            @AttributeOverride(name = "currency", column = @Column(name = "currency", length = 3))
    })
    private Money amount;

    protected Payment() {
    }

    public Payment(PaymentMethod method, PaymentType type, Money amount) {
        this.method = method;
        this.type = type;
        this.amount = amount;
    }

    public PaymentMethod getMethod() {
        return method;
    }

    public PaymentType getType() {
        return type;
    }

    public Money getAmount() {
        return amount;
    }
}
