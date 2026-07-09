package vn.essvn.erpcafe.identity.web;

import java.util.Set;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

/** Request/response payloads for role management. */
public final class RoleDtos {

    public record CreateRoleRequest(
            @NotBlank String name,
            String description,
            @NotEmpty Set<String> permissions) {
    }

    /** Payload for replacing or adding a role's permissions. */
    public record PermissionsRequest(
            @NotEmpty Set<String> permissions) {
    }

    public record RoleResponse(
            UUID id,
            UUID companyId,
            String name,
            String description,
            Set<String> permissions) {
    }

    private RoleDtos() {
    }
}
