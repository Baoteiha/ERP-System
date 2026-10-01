package vn.essvn.erpcafe.catalog.web;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

public final class RecipeDtos {

    public record SetRecipeRequest(
            @NotNull @Valid List<RecipeLineDto> lines,
            // the version the client loaded; omitted when it has none (first save)
            Long version) {
    }

    public record RecipeResponse(
            UUID productId,
            List<ConsumptionLineDto> lines,
            Long version) {
    }

    private RecipeDtos() {
    }
}
