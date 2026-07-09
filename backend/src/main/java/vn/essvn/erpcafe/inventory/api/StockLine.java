package vn.essvn.erpcafe.inventory.api;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * A quantity of one ingredient to deduct from stock. The sales module builds
 * these from catalog's recipe explosion. {@code unit} is expected to match the
 * ingredient's base unit (unit-of-measure conversion is a later concern).
 */
public record StockLine(UUID ingredientId, BigDecimal quantity, String unit) {
}
