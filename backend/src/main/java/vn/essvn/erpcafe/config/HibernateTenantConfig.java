package vn.essvn.erpcafe.config;

import org.hibernate.cfg.MultiTenancySettings;
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import vn.essvn.erpcafe.common.persistence.TenantIdentifierResolver;

/**
 * Class: HibernateTenantConfig
 * Purpose: Registers the tenant identifier resolver that drives Hibernate's {@code @TenantId} discriminator.
 */
@Configuration
public class HibernateTenantConfig {

    @Bean
    HibernatePropertiesCustomizer tenantIdentifierResolverCustomizer() {
        TenantIdentifierResolver resolver = new TenantIdentifierResolver();
        return properties -> properties.put(MultiTenancySettings.MULTI_TENANT_IDENTIFIER_RESOLVER, resolver);
    }
}
