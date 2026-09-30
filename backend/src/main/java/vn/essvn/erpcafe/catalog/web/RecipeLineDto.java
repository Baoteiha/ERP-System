package vn.essvn.erpcafe.catalog.web;

import java.math.BigDecimal;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/** One ingredient line of a base recipe, which must consume a positive quantity. */
public record RecipeLineDto(
        @NotNull UUID ingredientId,
        @NotNull @Positive BigDecimal quantity,
        @NotBlank String unit) {
}
