package vn.essvn.erpcafe.inventory.api;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Class: StockShortfall
 * Purpose: One ingredient a sale needs more of than the branch holds, in its base unit at ledger scale.
 */
public record StockShortfall(UUID ingredientId, String ingredientName, String unit, BigDecimal required,
        BigDecimal onHand) {
}
