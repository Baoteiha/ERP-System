package vn.essvn.erpcafe.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Binds {@code app.security.*} configuration: JWT signing/expiry and the
 * bootstrap admin credentials used to seed the first login, and the login throttle.
 */
@Validated
@ConfigurationProperties(prefix = "app.security")
public record SecurityProperties(@Valid @NotNull Jwt jwt, Bootstrap bootstrap, @Valid Login login) {

    public SecurityProperties {
        if (login == null) {
            login = new Login(10, Duration.ofMinutes(15), 20, 10_000);
        }
    }

    public record Jwt(
            @NotBlank @Size(min = 32) String secret,
            Duration accessTokenTtl,
            Duration refreshTokenTtl) {
    }

    /** Failed-login throttle: per email-and-address pair, per address alone, and the memory bound. */
    public record Login(
            @Min(1) int maxFailures,
            @NotNull Duration window,
            @Min(1) int maxFailuresPerAddress,
            @Min(1) int maxTrackedKeys) {
    }

    public record Bootstrap(
            String adminEmail,
            String adminPassword) {
    }
}
