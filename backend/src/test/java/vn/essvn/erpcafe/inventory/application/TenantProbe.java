package vn.essvn.erpcafe.inventory.application;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * Test-only bean living in {@code inventory.application} so {@code TenantRlsAspect}
 * advises it. Its transactional method reads back the {@code app.company_id} setting
 * on the transaction's own connection, letting a test verify the aspect ran inside
 * the transaction and set the value the queries will see.
 */
@Component
public class TenantProbe {

    @PersistenceContext
    private EntityManager entityManager;

    @Transactional
    public String currentTenant() {
        return (String) entityManager
                .createNativeQuery("select current_setting('app.company_id', true)")
                .getSingleResult();
    }
}
