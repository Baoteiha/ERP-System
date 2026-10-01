package vn.essvn.erpcafe.config;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.transaction.autoconfigure.TransactionManagerCustomizers;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import vn.essvn.erpcafe.common.persistence.TenantTransactionManager;

/**
 * Class: TenantTransactionConfig
 * Purpose: Makes the tenant-aware transaction manager the one every transaction in the application runs under.
 */
@Configuration
public class TenantTransactionConfig {

    // Replaces Spring Boot's JpaTransactionManager, customised the same way.
    @Bean
    PlatformTransactionManager transactionManager(ObjectProvider<TransactionManagerCustomizers> customizers) {
        TenantTransactionManager transactionManager = new TenantTransactionManager();
        customizers.ifAvailable(c -> c.customize(transactionManager));
        return transactionManager;
    }
}
