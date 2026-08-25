package vn.essvn.erpcafe.inventory.web;

import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public final class ItemDtos {

    public record ItemRequest(
            @Size(max = 64) String sku,
            @NotBlank @Size(max = 255) String name,
            // Set on create and fixed thereafter: an item is one supplier's product for one
            // ingredient, so repointing it would rewrite what past purchases meant. Ignored
            // on update.
            @NotNull UUID supplierId,
            @NotNull UUID ingredientId,
            // The unit this is bought in. Must measure the same thing as the ingredient's
            // base unit — kg against g is fine, kg against ml is rejected (422).
            @NotBlank @Size(max = 16) String unit,
            Boolean active,
            // Optimistic-lock guard for updates: the client echoes the version it loaded so a
            // stale-form edit is rejected (409) rather than silently overwriting a newer change.
            // Ignored on create.
            Long version) {
    }

    public record ItemResponse(
            UUID id,
            UUID companyId,
            String sku,
            String name,
            UUID supplierId,
            UUID ingredientId,
            String unit,
            boolean active,
            Long version) {
    }

    private ItemDtos() {
    }
}
