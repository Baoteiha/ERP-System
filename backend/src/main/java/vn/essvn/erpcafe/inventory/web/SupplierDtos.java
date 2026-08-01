package vn.essvn.erpcafe.inventory.web;

import java.util.UUID;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class SupplierDtos {

    public record SupplierRequest(
            @NotBlank @Size(max = 255) String name,
            @Size(max = 32) String contactPhone,
            @Email String contactEmail,
            @Size(max = 500) String address,
            Boolean active,
            // Optimistic-lock guard for updates: the client echoes the version it loaded so a
            // stale-form edit is rejected (409) rather than silently overwriting a newer change.
            // Ignored on create.
            Long version) {
    }

    public record SupplierResponse(
            UUID id,
            UUID companyId,
            String name,
            String contactPhone,
            String contactEmail,
            String address,
            boolean active,
            Long version) {
    }

    private SupplierDtos() {
    }
}
