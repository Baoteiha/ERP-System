package vn.essvn.erpcafe.catalog.api;

import java.util.Set;
import java.util.UUID;

/**
 * Published facade of the catalog module. The sales module uses this to price
 * line items without touching catalog internals. What a sale consumes is not asked
 * here: catalog answers the core's {@code inventory.api.ConsumptionResolver} port (ADR-0010).
 */
public interface CatalogApi {

    /**
     * The one pricing path: prices a single line (product + selected modifiers) at a branch and
     * returns the snapshot breakdown the sales module records on the order. A {@code null} branch
     * quotes the company-wide base price. Throws not-found if the product (or branch) is outside the
     * caller's company, and a business-rule error if the selection is not sellable.
     */
    PricedLine priceLine(UUID branchId, UUID productId, Set<UUID> modifierIds);
}
