package vn.essvn.erpcafe.catalog.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import vn.essvn.erpcafe.common.domain.AuditableEntity;

/**
 * The ingredient recipe for a product (1:1): the F&B bill of materials. It is kept as
 * a series of {@link RecipeVersion}s; an order line records the version in force when
 * it was taken, and its sale consumes that version.
 */
@Entity
@Table(name = "recipe", uniqueConstraints = @UniqueConstraint(name = "uk_recipe_product", columnNames = "product_id"))
public class Recipe extends AuditableEntity {

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    /** The version in force: what a line taken now records, and what an edit must start from. */
    @Column(name = "current_version_no", nullable = false)
    private int currentVersionNo;

    // Only ever added to: an open order may still consume an older version.
    @OneToMany(mappedBy = "recipe", cascade = CascadeType.PERSIST)
    private List<RecipeVersion> versions = new ArrayList<>();

    protected Recipe() {
    }

    public Recipe(UUID productId) {
        this.productId = productId;
    }

    public UUID getProductId() {
        return productId;
    }

    /** 0 until the first version is saved. */
    public int getCurrentVersionNo() {
        return currentVersionNo;
    }

    /** Saves the lines as the next version and puts it in force; earlier versions stay as they were. */
    public RecipeVersion revise(List<ConsumptionLine> lines) {
        RecipeVersion next = new RecipeVersion(this, currentVersionNo + 1, lines);
        versions.add(next);
        currentVersionNo = next.getVersionNo();
        return next;
    }
}
