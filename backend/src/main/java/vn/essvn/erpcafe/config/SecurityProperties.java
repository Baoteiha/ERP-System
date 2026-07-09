package vn.essvn.erpcafe.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds {@code app.security.*} configuration: JWT signing/expiry and the
 * bootstrap admin credentials used to seed the first login.
 */
@ConfigurationProperties(prefix = "app.security")
public record SecurityProperties(Jwt jwt, Bootstrap bootstrap) {

    public record Jwt(
            String secret,
            Duration accessTokenTtl,
            Duration refreshTokenTtl) {
    }

    public record Bootstrap(
            String adminEmail,
            String adminPassword) {
    }
}
