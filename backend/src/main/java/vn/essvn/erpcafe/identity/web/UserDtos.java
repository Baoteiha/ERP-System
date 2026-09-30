package vn.essvn.erpcafe.identity.web;

import java.time.Instant;
import java.util.UUID;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import vn.essvn.erpcafe.common.web.MaxUtf8Bytes;
import vn.essvn.erpcafe.identity.domain.UserStatus;

/** Request/response payloads for user management. */
public final class UserDtos {

    public record CreateUserRequest(
            @NotBlank @Email String email,
            @NotBlank @Size(min = 8, max = 100) @MaxUtf8Bytes(72) String password,
            @Size(max = 255) String fullName) {
    }

    public record GrantAccessRequest(
            @NotNull UUID branchId,
            @NotNull UUID roleId) {
    }

    public record UpdateUserRequest(
            @NotBlank @Email String email,
            @Size(max = 255) String fullName) {
    }

    public record UpdateStatusRequest(
            @NotNull UserStatus status) {
    }

    public record ChangePasswordRequest(
            @NotBlank String currentPassword,
            @NotBlank @Size(min = 8, max = 100) @MaxUtf8Bytes(72) String newPassword) {
    }

    public record ResetPasswordRequest(
            @NotBlank @Size(min = 8, max = 100) @MaxUtf8Bytes(72) String newPassword) {
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
