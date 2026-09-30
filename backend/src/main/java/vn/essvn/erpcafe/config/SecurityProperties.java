package vn.essvn.erpcafe.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Binds {@code app.security.*} configuration: JWT signing/expiry and the
 * bootstrap admin credentials used to seed the first login.
 */
@Validated
@ConfigurationProperties(prefix = "app.security")
public record SecurityProperties(@Valid @NotNull Jwt jwt, Bootstrap bootstrap) {

    public record Jwt(
            @NotBlank @Size(min = 32) String secret,
            Duration accessTokenTtl,
            Duration refreshTokenTtl) {
    }

    public record Bootstrap(
            String adminEmail,
            String adminPassword) {
    }
}
