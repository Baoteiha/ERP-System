package vn.essvn.erpcafe.identity.web;

import java.util.Set;
import java.util.UUID;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/** Request/response payloads for the auth endpoints. */
public final class AuthDtos {

    public record LoginRequest(
            @NotBlank @Email String email,
            @NotBlank String password) {
    }

    public record RefreshRequest(
            @NotBlank String refreshToken) {
    }

    public record TokenResponse(
            String accessToken,
            String refreshToken,
            String tokenType) {

        public static TokenResponse bearer(String accessToken, String refreshToken) {
            return new TokenResponse(accessToken, refreshToken, "Bearer");
        }
    }

    public record MeResponse(
            UUID userId,
            String email,
            UUID companyId,
            Set<UUID> branchIds,
            Set<String> roles,
            Set<String> permissions) {
    }

    private AuthDtos() {
    }
}
