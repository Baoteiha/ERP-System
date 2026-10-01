package vn.essvn.erpcafe.catalog.api;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Published facade of the catalog module. The sales module uses this to price
 * line items and to explode a product + chosen modifiers into ingredient
 * consumption for stock deduction — without touching catalog internals.
 */
public interface CatalogApi {

    /**
     * Explodes {@code quantity} units of a product plus its selected modifiers
     * into net ingredient consumption (recipe lines × quantity, adjusted by
     * modifier deltas). Empty if the product has no recipe yet. The result reflects the
     * recipe <em>now</em>; never use it to reverse a past sale — the recipe may have changed
     * since, so reversals read the recorded stock movements instead.
     */
    List<IngredientConsumption> explode(UUID productId, int quantity, Set<UUID> modifierIds);

    /**
     * The one pricing path: prices a single line (product + selected modifiers) at a branch and
     * returns the snapshot breakdown the sales module records on the order. A {@code null} branch
     * quotes the company-wide base price. Throws not-found if the product (or branch) is outside the
     * caller's company, and a business-rule error if the selection is not sellable.
     */
    PricedLine priceLine(UUID branchId, UUID productId, Set<UUID> modifierIds);
}
