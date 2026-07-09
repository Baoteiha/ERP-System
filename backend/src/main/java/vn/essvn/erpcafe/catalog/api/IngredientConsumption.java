package vn.essvn.erpcafe.catalog.api;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * A quantity of one ingredient consumed by a line item, after exploding the
 * product recipe plus selected modifier deltas. Consumed by the sales/inventory
 * modules for stock deduction and COGS.
 */
public record IngredientConsumption(UUID ingredientId, BigDecimal quantity, String unit) {
}
