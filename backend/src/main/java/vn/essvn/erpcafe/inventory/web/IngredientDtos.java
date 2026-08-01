package vn.essvn.erpcafe.inventory.web;

import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class IngredientDtos {

    public record IngredientRequest(
            @NotBlank @Size(max = 255) String name,
            @NotBlank @Size(max = 16) String baseUnit,
            @Size(max = 64) String category,
            Boolean active,
            // Optimistic-lock guard for updates: the client echoes the version it loaded so a
            // stale-form edit is rejected (409) rather than silently overwriting a newer change.
            // Ignored on create.
            Long version) {
    }

    public record IngredientResponse(
            UUID id,
            UUID companyId,
            String name,
            String baseUnit,
            String category,
            boolean active,
            Long version) {
    }

    private IngredientDtos() {
    }
}
