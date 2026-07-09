package vn.essvn.erpcafe.identity.web;

import java.time.Instant;
import java.util.UUID;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import vn.essvn.erpcafe.identity.domain.UserStatus;

/** Request/response payloads for user management. */
public final class UserDtos {

    public record CreateUserRequest(
            @NotBlank @Email String email,
            @NotBlank @Size(min = 8, max = 100) String password,
            @Size(max = 255) String fullName) {
    }

    public record GrantAccessRequest(
            @NotNull UUID branchId,
            @NotNull UUID roleId) {
    }

    public record UserResponse(
            UUID id,
            UUID companyId,
            String email,
            String fullName,
            UserStatus status,
            Instant createdAt,
            String createdBy) {
    }

    public record BranchAccessResponse(
            UUID branchId,
            UUID roleId) {
    }

    private UserDtos() {
    }
}
