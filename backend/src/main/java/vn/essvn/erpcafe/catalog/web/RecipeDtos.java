package vn.essvn.erpcafe.catalog.web;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

public final class RecipeDtos {

    public record SetRecipeRequest(
            @NotNull @Valid List<RecipeLineDto> lines) {
    }

    public record RecipeResponse(
            UUID productId,
            List<ConsumptionLineDto> lines) {
    }

    private RecipeDtos() {
    }
}
