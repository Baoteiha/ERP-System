package vn.essvn.erpcafe.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.PayloadApplicationEvent;
import org.springframework.context.event.ApplicationEventMulticaster;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import vn.essvn.erpcafe.common.context.TenantContext;
import vn.essvn.erpcafe.common.events.DomainEvent;
import vn.essvn.erpcafe.common.events.DomainEventDispatcher;
import vn.essvn.erpcafe.common.events.DomainEventPublisher;
import vn.essvn.erpcafe.organization.domain.Company;
import vn.essvn.erpcafe.organization.persistence.CompanyRepository;
import vn.essvn.erpcafe.support.AbstractIntegrationTest;

/**
 * Class: DomainEventOutboxTest
 * Purpose: Proves a domain event is written with its business transaction and delivered after commit, once, inside its company and in order.
 */
class DomainEventOutboxTest extends AbstractIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger();

    /** A test-only event, seen by the listener this class registers. */
    record Probe(String label) {
    }

    /** One delivery as the listener saw it: the event, the company in force, and whether a transaction was open. */
    private record Seen(UUID eventId, String label, UUID company, boolean inTransaction) {
    }

    @Autowired private DomainEventPublisher publisher;
    @Autowired private DomainEventDispatcher dispatcher;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private CompanyRepository companyRepository;
    @Autowired private ApplicationEventMulticaster multicaster;
    @Autowired @Qualifier("ownerDataSource") private DataSource ownerDataSource;

    private final List<Seen> seen = new CopyOnWriteArrayList<>();
    private final Set<String> failing = ConcurrentHashMap.newKeySet();
    private final List<UUID> companies = new ArrayList<>();
    private final ApplicationListener<ApplicationEvent> listener = this::onEvent;

    @BeforeEach
    void listen() {
        multicaster.addApplicationListener(listener);
    }

    @AfterEach
    void stopListening() {
        multicaster.removeApplicationListener(listener);
        // a probe's class exists only in tests: none may be left for a later dispatch to stumble on
        companies.forEach(c -> owner().update("DELETE FROM domain_event WHERE company_id = ?", c));
    }

    private void onEvent(ApplicationEvent event) {
        if (event instanceof PayloadApplicationEvent<?> payload && payload.getPayload() instanceof DomainEvent<?> e
                && e.payload() instanceof Probe probe) {
            seen.add(new Seen(e.id(), probe.label(), TenantContext.current().orElse(null),
                    TransactionSynchronizationManager.isActualTransactionActive()));
            if (failing.contains(probe.label())) {
                throw new IllegalStateException("probe " + probe.label() + " refused");
            }
        }
    }

    private JdbcTemplate owner() {
        return new JdbcTemplate(ownerDataSource);
    }

    private UUID company() {
        int n = SEQ.incrementAndGet();
        UUID id = TenantContext.runAsSystem(() -> companyRepository.save(
                new Company("Outbox Co %d".formatted(n), "OBX%d".formatted(n)))).getId();
        companies.add(id);
        return id;
    }

    private TransactionTemplate transaction() {
        return new TransactionTemplate(transactionManager);
    }

    private void publishAs(UUID company, String label) {
        TenantContext.runAs(company, () -> transaction().executeWithoutResult(s -> publisher.publish(new Probe(label))));
    }

    private Map<String, Object> row(UUID company, String label) {
        return owner().queryForMap("SELECT * FROM domain_event WHERE company_id = ? AND payload->>'label' = ?",
                company, label);
    }

    private List<String> labelsSeen() {
        return seen.stream().map(Seen::label).toList();
    }

    @Test
    void anEvent_isDeliveredAfterCommit_onceAndInsideItsCompany() {
        UUID company = company();
        publishAs(company, "a1");
        assertThat(seen).as("publishing hands nothing to the listeners").isEmpty();

        dispatcher.dispatchPending();
        dispatcher.dispatchPending();

        Map<String, Object> stored = row(company, "a1");
        assertThat(seen).singleElement().satisfies(s -> {
            assertThat(s.label()).isEqualTo("a1");
            assertThat(s.eventId()).isEqualTo(stored.get("id"));
            assertThat(s.company()).isEqualTo(company);
            assertThat(s.inTransaction()).isTrue();
        });
        assertThat(stored.get("processed_at")).isNotNull();
        assertThat(stored.get("event_type")).isEqualTo(Probe.class.getName());
    }

    @Test
    void anEventOfARolledBackTransaction_isNeverWritten() {
        UUID company = company();
        TenantContext.runAs(company, () -> transaction().executeWithoutResult(s -> {
            publisher.publish(new Probe("rolled-back"));
            s.setRollbackOnly();
        }));
        publishAs(company, "kept");

        dispatcher.dispatchPending();

        assertThat(labelsSeen()).containsExactly("kept");
        assertThat(owner().queryForObject("SELECT count(*) FROM domain_event WHERE company_id = ?", Long.class,
                company)).isEqualTo(1);
    }

    @Test
    void publishing_needsATransactionAndACompany() {
        UUID company = company();

        assertThatThrownBy(() -> TenantContext.runAs(company, () -> publisher.publish(new Probe("loose"))))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> transaction().executeWithoutResult(s -> publisher.publish(new Probe("nobody's"))))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aFailedDelivery_isRetriedLater_andHoldsBackOnlyItsOwnCompanysLaterEvents() {
        UUID a = company();
        UUID b = company();
        failing.add("a1");
        publishAs(a, "a1");
        publishAs(a, "a2");
        publishAs(b, "b1");

        dispatcher.dispatchPending();

        assertThat(labelsSeen()).containsExactly("a1", "b1");
        Map<String, Object> failed = row(a, "a1");
        assertThat(failed.get("processed_at")).isNull();
        assertThat(failed.get("attempts")).isEqualTo(1);
        assertThat((String) failed.get("last_error")).contains("probe a1 refused");
        assertThat(((Timestamp) failed.get("next_attempt_at")).toInstant()).isAfter(Instant.now());
        assertThat(row(a, "a2").get("processed_at")).isNull();

        // once the retry is due and the listener has recovered, both go, in the order they were written
        failing.clear();
        seen.clear();
        owner().update("UPDATE domain_event SET next_attempt_at = now() - interval '1 second' WHERE company_id = ?", a);
        dispatcher.dispatchPending();

        assertThat(labelsSeen()).containsExactly("a1", "a2");
        assertThat(row(a, "a2").get("processed_at")).isNotNull();
    }

    @Test
    void anEventThatKeepsFailing_isGivenUpAfterTheLastAttempt_andReleasesItsCompany() {
        UUID a = company();
        failing.add("poison");
        publishAs(a, "poison");
        publishAs(a, "next");
        owner().update("UPDATE domain_event SET attempts = ? WHERE company_id = ? AND payload->>'label' = 'poison'",
                DomainEventDispatcher.MAX_ATTEMPTS - 1, a);

        dispatcher.dispatchPending();
        dispatcher.dispatchPending();

        assertThat(labelsSeen()).containsExactly("poison", "next");
        assertThat(row(a, "poison").get("attempts")).isEqualTo(DomainEventDispatcher.MAX_ATTEMPTS);
        assertThat(row(a, "poison").get("processed_at")).isNull();
        assertThat(row(a, "next").get("processed_at")).isNotNull();
    }

    @Test
    void anEventBeingDeliveredElsewhere_holdsBackItsCompanysLaterEvents() throws Exception {
        UUID a = company();
        publishAs(a, "first");
        publishAs(a, "second");

        // another dispatcher's delivery holds the row's lock until its transaction ends
        try (Connection other = ownerDataSource.getConnection()) {
            other.setAutoCommit(false);
            try (PreparedStatement lock = other.prepareStatement("SELECT id FROM domain_event WHERE id = ? FOR UPDATE")) {
                lock.setObject(1, row(a, "first").get("id"));
                lock.executeQuery();
            }
            dispatcher.dispatchPending();
            assertThat(seen).isEmpty();
            other.rollback();
        }

        dispatcher.dispatchPending();

        assertThat(labelsSeen()).containsExactly("first", "second");
    }
}
