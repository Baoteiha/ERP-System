package vn.essvn.erpcafe.inventory.api;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Class: ConsumptionResolver
 * Purpose: The core's port for what a sold product consumes, implemented by the module owning recipes so sales never calls it.
 */
public interface ConsumptionResolver {

    /**
     * The recipe version in force for the company's product, for a sale to record as it takes the
     * line; empty when the product has no recipe.
     */
    Optional<UUID> currentRecipeVersion(UUID companyId, UUID productId);

    /**
     * What {@code quantity} of the product consumes at the recorded recipe version, with the
     * selected modifiers' deltas as they stand now, netted per ingredient and unit. A {@code null}
     * version means the product had no recipe when the line was taken: nothing is consumed. Throws
     * not-found when the version is not one of the company's product's.
     */
    List<StockLine> consumptionFor(UUID companyId, UUID productId, int quantity, Set<UUID> modifierIds,
            UUID recipeVersionId);
}
