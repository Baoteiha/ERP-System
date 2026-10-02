package vn.essvn.erpcafe.catalog.domain;

import java.math.BigDecimal;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Embeddable;
import vn.essvn.erpcafe.common.domain.Unit;
import vn.essvn.erpcafe.common.domain.UnitConverter;

/**
 * Class: ConsumptionLine
 * Purpose: One ingredient quantity in a known unit, the shape shared by a recipe line and a modifier delta.
 */
@Embeddable
public record ConsumptionLine(
        @Column(name = "ingredient_id", nullable = false) UUID ingredientId,
        @Column(name = "quantity", nullable = false, precision = 19, scale = 4) BigDecimal quantity,
        @Convert(converter = UnitConverter.class) @Column(name = "unit", nullable = false, length = 16) Unit unit) {
}
