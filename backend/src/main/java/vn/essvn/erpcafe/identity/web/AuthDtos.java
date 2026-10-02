package vn.essvn.erpcafe.identity.web;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import vn.essvn.erpcafe.common.web.MaxUtf8Bytes;
import vn.essvn.erpcafe.identity.domain.AuthEvent;
import vn.essvn.erpcafe.identity.domain.AuthEventType;

/** Request/response payloads for the auth endpoints. */
public final class AuthDtos {

    public record LoginRequest(
            @NotBlank @Email String email,
            @NotBlank String password) {
    }

    public record PasswordResetRequest(
            @NotBlank @Email String email) {
    }

    /** {@code token}: 64 hex characters as issued; anything longer is no token of ours. */
    public record PasswordResetConfirmRequest(
            @NotBlank @Size(max = 64) String token,
            @NotBlank @Size(min = 8, max = 100) @MaxUtf8Bytes(72) String newPassword) {
    }

    /** The refresh token travels in an HttpOnly cookie, never in the body. */
    public record TokenResponse(
            String accessToken,
            String tokenType) {

        public static TokenResponse bearer(String accessToken) {
            return new TokenResponse(accessToken, "Bearer");
        }
    }

    /** {@code permissions}: held at any branch; {@code permissionsByBranch}: what each branch's role allows. */
    public record MeResponse(
            UUID userId,
            String email,
            UUID companyId,
            Set<UUID> branchIds,
            Set<String> roles,
            Set<String> permissions,
            Map<UUID, Set<String>> permissionsByBranch) {
    }

    /** One entry of the authentication audit trail; see {@link AuthEvent}. */
    public record AuthEventResponse(
            UUID id,
            UUID userId,
            AuthEventType type,
            String ip,
            Instant occurredAt,
            Map<String, Object> detail) {

        public static AuthEventResponse of(AuthEvent event) {
            return new AuthEventResponse(event.id(), event.userId(), event.type(), event.ip(), event.occurredAt(),
                    event.detail());
        }
    }

    private AuthDtos() {
    }
}
