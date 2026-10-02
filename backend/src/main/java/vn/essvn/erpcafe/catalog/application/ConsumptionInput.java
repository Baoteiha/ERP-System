package vn.essvn.erpcafe.catalog.application;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Class: ConsumptionInput
 * Purpose: One ingredient line as the client sent it, its unit still text, so a list's problems are reported together.
 */
public record ConsumptionInput(UUID ingredientId, BigDecimal quantity, String unit) {
}
