package vn.essvn.erpcafe.config;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.flyway.autoconfigure.FlywayDataSource;
import org.springframework.boot.flyway.autoconfigure.FlywayProperties;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import com.zaxxer.hikari.HikariDataSource;

import vn.essvn.erpcafe.common.persistence.TenantRoutingDataSource;

/**
 * Class: DataSourceConfig
 * Purpose: Defines the app role's and the schema owner's connection pools and routes each transaction to one of them.
 */
@Configuration
@EnableConfigurationProperties({FlywayProperties.class, OwnerPoolProperties.class})
public class DataSourceConfig {

    /** {@code spring.datasource.*}: erp_app, NOSUPERUSER NOBYPASSRLS. */
    @Bean
    @ConfigurationProperties("spring.datasource.hikari")
    HikariDataSource appDataSource(DataSourceProperties properties) {
        HikariDataSource dataSource = properties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
        dataSource.setPoolName("app");
        return dataSource;
    }

    /**
     * {@code spring.flyway.*}: the schema owner with BYPASSRLS, which runs Flyway and the system scope;
     * {@code app.datasource.owner.*} sizes it ({@code spring.datasource.hikari.*} binds only the app pool).
     */
    @Bean
    @FlywayDataSource
    HikariDataSource ownerDataSource(DataSourceProperties app, FlywayProperties flyway, OwnerPoolProperties pool) {
        Assert.state(StringUtils.hasText(flyway.getUser()),
                "spring.flyway.user must name the schema owner, a role with BYPASSRLS");
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setPoolName("owner");
        dataSource.setJdbcUrl(flyway.getUrl() != null ? flyway.getUrl() : app.determineUrl());
        dataSource.setUsername(flyway.getUser());
        dataSource.setPassword(flyway.getPassword());
        dataSource.setMaximumPoolSize(pool.maximumPoolSize());
        dataSource.setConnectionTimeout(pool.connectionTimeout().toMillis());
        dataSource.setMinimumIdle(1);
        return dataSource;
    }

    @Bean
    @Primary
    DataSource dataSource(@Qualifier("appDataSource") DataSource app, @Qualifier("ownerDataSource") DataSource owner) {
        return new TenantRoutingDataSource(app, owner);
    }
}
