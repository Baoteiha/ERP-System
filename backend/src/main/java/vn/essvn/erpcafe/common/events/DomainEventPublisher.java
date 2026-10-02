package vn.essvn.erpcafe.common.events;

import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.json.JsonMapper;
import vn.essvn.erpcafe.common.context.TenantContext;

/**
 * Class: DomainEventPublisher
 * Purpose: Records a domain event in the transaction that makes it true, for delivery once that transaction commits.
 */
@Component
public class DomainEventPublisher {

    private final JdbcTemplate jdbc;
    private final JsonMapper json;

    public DomainEventPublisher(JdbcTemplate jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /**
     * Writes {@code event} as the company in force, inside the caller's transaction: it is delivered
     * if that transaction commits and never exists if it rolls back. Listeners do not run here; the
     * dispatcher hands the event to them after the commit.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void publish(Object event) {
        UUID companyId = TenantContext.companyForNewRow();
        jdbc.update("INSERT INTO domain_event (id, company_id, event_type, payload) VALUES (?, ?, ?, ?::jsonb)",
                UUID.randomUUID(), companyId, event.getClass().getName(), json.writeValueAsString(event));
    }
}
