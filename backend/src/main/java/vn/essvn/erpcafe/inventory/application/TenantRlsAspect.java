package vn.essvn.erpcafe.inventory.application;

import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import vn.essvn.erpcafe.identity.security.CurrentUser;

/**
 * Sets the PostgreSQL {@code app.company_id} run-time setting at the start of each
 * transactional inventory operation, from the authenticated user's company, so the
 * Row-Level Security policy (see the V7 migration) filters every query automatically.
 *
 * <p>{@code set_config(..., true)} is transaction-local — it applies to the current
 * transaction's connection and is discarded at commit/rollback, so a pooled
 * connection never carries one tenant's value into the next request.
 *
 * <p>Ordering matters: this aspect is {@code LOWEST_PRECEDENCE} and the transaction
 * advisor is pinned to order 0 (see {@code RlsConfig}), so this runs <em>inside</em>
 * the transaction and lands on the same connection the queries use. If no user is
 * authenticated, nothing is set and the policy fails closed (sees no rows).
 */
@Aspect
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class TenantRlsAspect {

    @PersistenceContext
    private EntityManager entityManager;

    private final CurrentUser currentUser;

    public TenantRlsAspect(CurrentUser currentUser) {
        this.currentUser = currentUser;
    }

    @Before("execution(* vn.essvn.erpcafe.inventory.application..*(..)) "
            + "&& !within(vn.essvn.erpcafe.inventory.application.TenantRlsAspect)")
    public void applyTenant() {
        currentUser.get().ifPresent(principal -> {
            if (principal.companyId() != null) {
                entityManager.createNativeQuery("select set_config('app.company_id', :companyId, true)")
                        .setParameter("companyId", principal.companyId().toString())
                        .getSingleResult();
            }
        });
    }
}
