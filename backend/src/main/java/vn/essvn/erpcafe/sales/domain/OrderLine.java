package vn.essvn.erpcafe.sales.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import vn.essvn.erpcafe.common.domain.BaseEntity;
import vn.essvn.erpcafe.common.domain.Money;

/**
 * One product line on an order, with snapshots of the product name and unit
 * price, the chosen modifiers, an optional line discount, and the computed line
 * total. The FK to the order is managed by {@code Order.lines}.
 */
@Entity
@Table(name = "order_line")
public class OrderLine extends BaseEntity {

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Column(name = "product_name", nullable = false)
    private String productName;

    /** Snapshot of the product's base unit price (before modifiers). */
    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "amount", column = @Column(name = "unit_price", precision = 19, scale = 4)),
            @AttributeOverride(name = "currency", column = @Column(name = "currency", length = 3))
    })
    private Money unitPrice;

    @Column(name = "quantity", nullable = false)
    private int quantity;

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "amount", column = @Column(name = "line_discount", precision = 19, scale = 4)),
            @AttributeOverride(name = "currency", column = @Column(name = "discount_currency", length = 3))
    })
    private Money lineDiscount;

    /** (unitPrice + Σ modifier deltas) × quantity − lineDiscount. */
    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "amount", column = @Column(name = "line_total", precision = 19, scale = 4)),
            @AttributeOverride(name = "currency", column = @Column(name = "total_currency", length = 3))
    })
    private Money lineTotal;

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @JoinColumn(name = "order_line_id")
    private List<OrderLineModifier> modifiers = new ArrayList<>();

    protected OrderLine() {
    }

    public OrderLine(UUID productId, String productName, Money unitPrice, int quantity, Money lineDiscount) {
        this.productId = productId;
        this.productName = productName;
        this.unitPrice = unitPrice;
        this.quantity = quantity;
        this.lineDiscount = lineDiscount;
    }

    public UUID getProductId() {
        return productId;
    }

    public String getProductName() {
        return productName;
    }

    public Money getUnitPrice() {
        return unitPrice;
    }

    public int getQuantity() {
        return quantity;
    }

    public Money getLineDiscount() {
        return lineDiscount;
    }

    public Money getLineTotal() {
        return lineTotal;
    }

    public void setLineTotal(Money lineTotal) {
        this.lineTotal = lineTotal;
    }

    public List<OrderLineModifier> getModifiers() {
        return modifiers;
    }

    public void addModifier(OrderLineModifier modifier) {
        this.modifiers.add(modifier);
    }

    public Set<UUID> modifierIds() {
        return modifiers.stream()
                .map(OrderLineModifier::getModifierId)
                .filter(id -> id != null)
                .collect(Collectors.toSet());
    }
}
