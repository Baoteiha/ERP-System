package vn.essvn.erpcafe.catalog.api;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import vn.essvn.erpcafe.common.domain.Money;

/**
 * Published facade of the catalog module. The sales module uses this to price
 * line items and to explode a product + chosen modifiers into ingredient
 * consumption for stock deduction — without touching catalog internals.
 */
public interface CatalogApi {

    /** Whether the product exists (and is not deleted). */
    boolean productExists(UUID productId);

    /**
     * Unit price for a product at a branch with the chosen modifiers:
     * (branch price override, else base price) + sum of modifier price deltas.
     */
    Money priceOf(UUID productId, UUID branchId, Set<UUID> modifierIds);

    /**
     * Explodes {@code quantity} units of a product plus its selected modifiers
     * into net ingredient consumption (recipe lines × quantity, adjusted by
     * modifier deltas). Empty if the product has no recipe yet.
     */
    List<IngredientConsumption> explode(UUID productId, int quantity, Set<UUID> modifierIds);

    /**
     * Prices a single line (product + selected modifiers) at a branch and returns
     * the snapshot breakdown (product name, base price, per-modifier deltas, unit total)
     * the sales module records on the order.
     */
    PricedLine priceLine(UUID productId, UUID branchId, Set<UUID> modifierIds);

    /**
     * Validates the selected modifiers against the product's modifier groups'
     * min/max selection rules (e.g. "pick exactly one size"). Throws if invalid.
     */
    void validateModifierSelection(UUID productId, Set<UUID> modifierIds);
}
