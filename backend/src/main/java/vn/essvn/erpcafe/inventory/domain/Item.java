package vn.essvn.erpcafe.inventory.domain;

import java.math.BigDecimal;
import java.util.UUID;

import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import vn.essvn.erpcafe.common.domain.TenantOwnedEntity;

/**
 * What a supplier sells us, and which {@link Ingredient} it becomes once received.
 * Suppliers sell kilograms and litres; stock is counted in grams and millilitres.
 * This is the bridge, and the only place that knowledge lives.
 *
 * <p>Not to be confused with {@code catalog.Product}, which is what <em>we</em> sell a
 * customer, nor with {@link StockItem}, which is an ingredient's balance at a branch.
 * An item is bought, a product is sold.
 *
 * <p>Many items may point at one ingredient — the same beans from two suppliers, or
 * the same supplier's 1 kg bag and 25 kg sack — but an item maps to exactly one
 * ingredient. That direction is what makes receiving a single tap: a delivery already
 * knows which stock it lands in, with nothing to decide per receipt.
 *
 * <p>{@code unit} is the unit the item is <em>bought</em> in, and must share a
 * dimension with the ingredient's base unit ({@code ItemService} enforces this on
 * write). Conversion is then a pure function of the two units, needing no lookup.
 * An optional pack ("hộp" of 100 pc) is a label over a quantity of {@code unit} (ADR-0001); orders
 * and receipts count it whole.
 *
 * <p>Company-scoped master data, soft-deletable. Uniqueness — one row per
 * {@code (supplier, sku)} and per {@code (supplier, ingredient, unit)} among live
 * rows — is enforced by partial unique indexes in the V9 migration rather than table
 * constraints, since {@code sku} is optional and deleted rows must not block reuse.
 */
@Entity
@Table(name = "item")
@SQLDelete(sql = "UPDATE item SET deleted = true WHERE id = ? AND version = ?")
@SQLRestriction("deleted = false")
public class Item extends TenantOwnedEntity {

    /** The supplier's own product code, when they use one. Optional, and their vocabulary — not ours. */
    @Column(name = "sku", length = 64)
    private String sku;

    /**
     * The supplier's product name. Worth keeping verbatim rather than tidying: it is the
     * string that appears on their invoices, so it is what future invoice matching keys on.
     */
    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "supplier_id", nullable = false)
    private UUID supplierId;

    @Column(name = "ingredient_id", nullable = false)
    private UUID ingredientId;

    @Column(name = "unit", nullable = false, length = 16)
    private String unit;

    /** The pack's name ("hộp"), or null when the item is bought loose. */
    @Column(name = "pack_label", length = 32)
    private String packLabel;

    /** How many of {@code unit} one pack holds (100 for a box of 100 pc), or null without a pack. */
    @Column(name = "pack_size", precision = 19, scale = 4)
    private BigDecimal packSize;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "deleted", nullable = false)
    private boolean deleted = false;

    protected Item() {
    }

    public Item(UUID supplierId, UUID ingredientId, String name, String unit) {
        this.supplierId = supplierId;
        this.ingredientId = ingredientId;
        this.name = name;
        this.unit = unit;
    }

    public String getSku() {
        return sku;
    }

    public void setSku(String sku) {
        this.sku = sku;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    /**
     * Which supplier sells this. Set once: an item <em>is</em> a supplier's product, so
     * repointing it would rewrite the meaning of every purchase already made against it.
     * Sell the same thing through a different supplier by creating a second item.
     */
    public UUID getSupplierId() {
        return supplierId;
    }

    /** Which ingredient this becomes in stock. Set once, for the same reason as the supplier. */
    public UUID getIngredientId() {
        return ingredientId;
    }

    public String getUnit() {
        return unit;
    }

    public void setUnit(String unit) {
        this.unit = unit;
    }

    public String getPackLabel() {
        return packLabel;
    }

    public BigDecimal getPackSize() {
        return packSize;
    }

    public boolean hasPack() {
        return packLabel != null;
    }

    /** Sets both halves of the pack together, or clears both; {@code ItemService} has validated them. */
    public void setPack(String label, BigDecimal size) {
        this.packLabel = label;
        this.packSize = size;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }
}
