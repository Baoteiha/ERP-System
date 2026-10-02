package vn.essvn.erpcafe.config;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Binds {@code app.security.*} configuration: JWT signing/expiry and the
 * bootstrap admin credentials used to seed the first login, the login throttle,
 * and password reset by email.
 */
@Validated
@ConfigurationProperties(prefix = "app.security")
public record SecurityProperties(@Valid @NotNull Jwt jwt, Bootstrap bootstrap, @Valid Login login,
        @Valid PasswordReset passwordReset) {

    public SecurityProperties {
        if (login == null) {
            login = new Login(10, Duration.ofMinutes(15), 20);
        }
        if (passwordReset == null) {
            passwordReset = new PasswordReset(Duration.ofMinutes(30), "http://localhost:4200/reset-password");
        }
    }

    /**
     * {@code keys}: every key a token may be signed with. New tokens are signed with the one marked
     * active; the others still verify the tokens they signed, so a key is rotated without signing anyone
     * out: add the new key as active, mark the old one inactive, and remove it once the access token TTL
     * has passed.
     */
    public record Jwt(
            List<@Valid Key> keys,
            Duration accessTokenTtl,
            Duration refreshTokenTtl) {

        public Jwt {
            if (keys == null || keys.isEmpty()) {
                throw new IllegalArgumentException("app.security.jwt.keys must name at least one key");
            }
            if (keys.stream().filter(Key::active).count() != 1) {
                throw new IllegalArgumentException("app.security.jwt.keys must mark exactly one active key");
            }
            Set<String> kids = new HashSet<>();
            for (Key key : keys) {
                if (!kids.add(key.kid())) {
                    throw new IllegalArgumentException("app.security.jwt.keys names kid " + key.kid() + " twice");
                }
            }
            keys = List.copyOf(keys);
        }

        /** The key new tokens are signed with. */
        public Key activeKey() {
            return keys.stream().filter(Key::active).findFirst().orElseThrow();
        }
    }

    /** One HS256 signing key; {@code kid} goes in the header of every token it signs. */
    public record Key(
            @NotBlank String kid,
            @NotBlank @Size(min = 32) String secret,
            boolean active) {

        // a key set printed in a log or a startup failure must not carry the secrets
        @Override
        public String toString() {
            return "Key[kid=" + kid + ", active=" + active + "]";
        }
    }

    /** Failed-login throttle: the limits per email-and-address pair and per address alone, within the window. */
    public record Login(
            @Min(1) int maxFailures,
            @NotNull Duration window,
            @Min(1) int maxFailuresPerAddress) {
    }

    /**
     * How long a mailed reset link works, and the page it opens; the link adds {@code ?token=...}. Each
     * setting has its own default, so a profile may set one without the other.
     */
    public record PasswordReset(
            @DefaultValue("30m") @NotNull Duration ttl,
            @DefaultValue("http://localhost:4200/reset-password") @NotBlank String link) {
    }

    public record Bootstrap(
            String adminEmail,
            String adminPassword) {
    }
}
