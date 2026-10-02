package vn.essvn.erpcafe.common.persistence;

import java.util.function.Supplier;

import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import vn.essvn.erpcafe.common.context.TenantContext;

/**
 * Class: SystemTransactions
 * Purpose: Runs work that must see every company in a transaction of its own, on the schema owner's connection.
 */
@Component
public class SystemTransactions {

    private final TransactionTemplate requiresNew;

    public SystemTransactions(PlatformTransactionManager transactionManager) {
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Runs {@code work} as the system. The transaction is always a new one: a session and a
     * connection take their scope when the transaction begins, so joining a caller's would keep
     * the caller's company and its filtered connection.
     */
    public <T> T run(Supplier<T> work) {
        refuseNesting();
        return TenantContext.runAsSystem(() -> requiresNew.execute(status -> work.get()));
    }

    public void run(Runnable work) {
        refuseNesting();
        TenantContext.runAsSystem(() -> requiresNew.executeWithoutResult(status -> work.run()));
    }

    // One inside another would hold two owner connections at once; enough requests doing that together
    // take the whole pool and then wait on each other until the borrow times out.
    private static void refuseNesting() {
        if (TenantContext.isSystem() && TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("A system transaction is already open on this thread");
        }
    }
}
