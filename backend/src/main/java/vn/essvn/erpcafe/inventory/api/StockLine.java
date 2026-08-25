package vn.essvn.erpcafe.inventory.api;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * A quantity of one ingredient to deduct from stock. The sales module builds
 * these from catalog's recipe explosion. {@code unit} may be any unit sharing
 * the ingredient's dimension — the ledger converts it to the base unit on
 * entry (ADR-0006); a blank unit means the quantity is already in base units.
 */
public record StockLine(UUID ingredientId, BigDecimal quantity, String unit) {
}
