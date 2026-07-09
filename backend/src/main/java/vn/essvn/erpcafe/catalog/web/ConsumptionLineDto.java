package vn.essvn.erpcafe.catalog.web;

import java.math.BigDecimal;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** One ingredient line for a recipe or a modifier delta. */
public record ConsumptionLineDto(
        @NotNull UUID ingredientId,
        @NotNull BigDecimal quantity,
        @NotBlank String unit) {
}
