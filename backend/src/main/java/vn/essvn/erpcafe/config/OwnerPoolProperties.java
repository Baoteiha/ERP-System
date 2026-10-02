package vn.essvn.erpcafe.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Class: OwnerPoolProperties
 * Purpose: Binds app.datasource.owner.*, the size and borrow timeout of the schema owner's connection pool.
 */
@Validated
@ConfigurationProperties(prefix = "app.datasource.owner")
public record OwnerPoolProperties(@Min(1) int maximumPoolSize, @NotNull Duration connectionTimeout) {
}
