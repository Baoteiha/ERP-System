package vn.essvn.erpcafe.catalog.application;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Service-layer input for one ingredient line (recipe line or modifier delta):
 * an ingredient, a signed quantity, and a unit.
 */
public record ConsumptionLine(UUID ingredientId, BigDecimal quantity, String unit) {
}
