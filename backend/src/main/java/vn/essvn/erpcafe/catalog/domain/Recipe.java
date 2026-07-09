package vn.essvn.erpcafe.catalog.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import vn.essvn.erpcafe.common.domain.AuditableEntity;

/**
 * The ingredient recipe for a product (1:1). A sale explodes this into
 * ingredient consumption for stock deduction (wired in the sales phase).
 */
@Entity
@Table(name = "recipe", uniqueConstraints = @UniqueConstraint(name = "uk_recipe_product", columnNames = "product_id"))
public class Recipe extends AuditableEntity {

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @JoinColumn(name = "recipe_id")
    private List<RecipeLine> lines = new ArrayList<>();

    protected Recipe() {
    }

    public Recipe(UUID productId) {
        this.productId = productId;
    }

    public UUID getProductId() {
        return productId;
    }

    public List<RecipeLine> getLines() {
        return lines;
    }

    public void replaceLines(List<RecipeLine> newLines) {
        this.lines.clear();
        this.lines.addAll(newLines);
    }
}
