package vn.essvn.erpcafe.common.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;

import org.hibernate.Session;
import org.springframework.orm.jpa.EntityManagerHolder;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityTransaction;
import vn.essvn.erpcafe.common.context.TenantContext;

/**
 * Class: TenantTransactionManager
 * Purpose: Begins every JPA transaction in the company in force, so the database's row-level security filters what it touches.
 */
public class TenantTransactionManager extends JpaTransactionManager {

    // Transaction-local (third argument true): the setting ends at commit or rollback, so a pooled
    // connection never carries one company into its next borrower.
    private static final String SET_COMPANY = "select set_config('app.company_id', ?, true)";

    /**
     * Sets {@code app.company_id} on the new transaction's connection. With no company in force
     * nothing is set: without a tenant the policies match no rows, and the system scope works on
     * the owner connection, which row-level security does not filter.
     */
    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {
        super.doBegin(transaction, definition);
        Optional<UUID> company = TenantContext.current();
        if (company.isEmpty()) {
            return;
        }
        EntityManager em = ((EntityManagerHolder) TransactionSynchronizationManager
                .getResource(obtainEntityManagerFactory())).getEntityManager();
        try {
            em.unwrap(Session.class).doWork(connection -> setCompany(connection, company.get()));
        } catch (RuntimeException ex) {
            abandon(transaction, em);
            throw new CannotCreateTransactionException("Could not begin the transaction in its company", ex);
        }
    }

    private static void setCompany(Connection connection, UUID companyId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(SET_COMPANY)) {
            statement.setString(1, companyId.toString());
            statement.execute();
        }
    }

    // A begun transaction the caller never receives: roll it back and unbind it, as a failed begin would.
    private void abandon(Object transaction, EntityManager em) {
        try {
            EntityTransaction tx = em.getTransaction();
            if (tx.isActive()) {
                tx.rollback();
            }
        } finally {
            doCleanupAfterCompletion(transaction);
        }
    }
}
