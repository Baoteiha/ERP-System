package vn.essvn.erpcafe.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import vn.essvn.erpcafe.common.context.TenantContext;
import vn.essvn.erpcafe.support.AbstractIntegrationTest;

/**
 * Class: RlsEverywhereTest
 * Purpose: Proves every transaction begins in its tenant's company, so row-level security filters whatever it touches.
 */
class RlsEverywhereTest extends AbstractIntegrationTest {

    private static final String TENANT_SETTING = "select current_setting('app.company_id', true)";

    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;

    private String settingInATransaction() {
        return new TransactionTemplate(transactionManager)
                .execute(status -> jdbc.queryForObject(TENANT_SETTING, String.class));
    }

    @Test
    void everyTransaction_beginsInTheCompanyInForce() {
        UUID company = UUID.randomUUID();

        assertThat(TenantContext.runAs(company, this::settingInATransaction)).isEqualTo(company.toString());
    }

    @Test
    void aNewTransactionInsideAnother_beginsInTheCompanyToo() {
        UUID company = UUID.randomUUID();
        TransactionTemplate requiresNew = new TransactionTemplate(transactionManager);
        requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        String inner = TenantContext.runAs(company, () -> new TransactionTemplate(transactionManager).execute(
                outer -> requiresNew.execute(status -> jdbc.queryForObject(TENANT_SETTING, String.class))));

        assertThat(inner).isEqualTo(company.toString());
    }

    @Test
    void withNoCompany_noneIsSet() {
        // A pooled connection reads the setting back as "" once a past transaction set it; both mean none.
        assertThat(settingInATransaction()).isNullOrEmpty();
        assertThat(TenantContext.runAsSystem(this::settingInATransaction)).isNullOrEmpty();
    }

    @Test
    void theCompany_endsWithItsTransaction() {
        UUID company = UUID.randomUUID();
        TenantContext.runAs(company, this::settingInATransaction);

        assertThat(settingInATransaction()).isNullOrEmpty();
    }
}
