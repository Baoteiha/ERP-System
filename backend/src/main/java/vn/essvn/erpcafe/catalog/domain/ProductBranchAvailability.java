package vn.essvn.erpcafe.catalog.domain;

import java.util.UUID;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import vn.essvn.erpcafe.common.domain.BranchScopedEntity;
import vn.essvn.erpcafe.common.domain.Money;

/**
 * Per-branch override for a product: whether it's available at a branch and,
 * optionally, a branch-specific price that overrides the product's base price.
 * Branch-scoped.
 */
@Entity
@Table(name = "product_branch_availability",
        uniqueConstraints = @UniqueConstraint(name = "uk_pba_product_branch", columnNames = {"product_id", "branch_id"}))
public class ProductBranchAvailability extends BranchScopedEntity {

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Column(name = "available", nullable = false)
    private boolean available = true;

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "amount", column = @Column(name = "price_override", precision = 19, scale = 4)),
            @AttributeOverride(name = "currency", column = @Column(name = "price_currency", length = 3))
    })
    private Money priceOverride;

    protected ProductBranchAvailability() {
    }

    public ProductBranchAvailability(UUID productId, UUID branchId, boolean available, Money priceOverride) {
        this.productId = productId;
        setBranchId(branchId);
        this.available = available;
        this.priceOverride = priceOverride;
    }

    public UUID getProductId() {
        return productId;
    }

    public boolean isAvailable() {
        return available;
    }

    public void setAvailable(boolean available) {
        this.available = available;
    }

    public Money getPriceOverride() {
        return priceOverride;
    }

    public void setPriceOverride(Money priceOverride) {
        this.priceOverride = priceOverride;
    }
}
