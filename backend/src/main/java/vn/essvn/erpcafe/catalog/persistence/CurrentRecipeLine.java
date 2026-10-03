package vn.essvn.erpcafe.catalog.persistence;

import java.math.BigDecimal;
import java.util.UUID;

import vn.essvn.erpcafe.catalog.domain.ConsumptionLine;
import vn.essvn.erpcafe.common.domain.Unit;

/**
 * Class: CurrentRecipeLine
 * Purpose: One line of the recipe version in force for a product, read together with the product it belongs to.
 */
public record CurrentRecipeLine(UUID productId, UUID ingredientId, BigDecimal quantity, Unit unit) {

    public ConsumptionLine line() {
        return new ConsumptionLine(ingredientId, quantity, unit);
    }
}
