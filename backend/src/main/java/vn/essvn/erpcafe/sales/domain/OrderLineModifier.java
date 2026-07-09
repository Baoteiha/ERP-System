package vn.essvn.erpcafe.sales.domain;

import java.util.UUID;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import vn.essvn.erpcafe.common.domain.BaseEntity;
import vn.essvn.erpcafe.common.domain.Money;

/**
 * A modifier chosen on an order line, snapshotted at order time (name + price
 * delta) so later catalog changes never rewrite history. The FK to the order
 * line is managed by {@code OrderLine.modifiers}.
 */
@Entity
@Table(name = "order_line_modifier")
public class OrderLineModifier extends BaseEntity {

    @Column(name = "modifier_id")
    private UUID modifierId;

    @Column(name = "modifier_name", nullable = false)
    private String modifierName;

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "amount", column = @Column(name = "price_delta", precision = 19, scale = 4)),
            @AttributeOverride(name = "currency", column = @Column(name = "currency", length = 3))
    })
    private Money priceDelta;

    protected OrderLineModifier() {
    }

    public OrderLineModifier(UUID modifierId, String modifierName, Money priceDelta) {
        this.modifierId = modifierId;
        this.modifierName = modifierName;
        this.priceDelta = priceDelta;
    }

    public UUID getModifierId() {
        return modifierId;
    }

    public String getModifierName() {
        return modifierName;
    }

    public Money getPriceDelta() {
        return priceDelta;
    }
}
