package vn.essvn.erpcafe.common.events;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.ClassUtils;

import tools.jackson.databind.json.JsonMapper;
import vn.essvn.erpcafe.common.context.TenantContext;
import vn.essvn.erpcafe.common.persistence.SystemTransactions;

/**
 * Class: DomainEventDispatcher
 * Purpose: Delivers committed domain events to their listeners, each inside its own company and transaction, in the order they were recorded.
 */
@Component
public class DomainEventDispatcher {

    private static final Logger log = LoggerFactory.getLogger(DomainEventDispatcher.class);

    /** Deliveries tried before an event is given up on; it then stays unprocessed for someone to look at. */
    public static final int MAX_ATTEMPTS = 10;

    private static final int BATCH_SIZE = 100;
    /** The wait before a retry, times the attempts made so far. */
    private static final Duration RETRY_DELAY = Duration.ofSeconds(30);
    private static final int MAX_ERROR_LENGTH = 2000;

    // Due events, oldest first, leaving out any whose company has an earlier event waiting for a retry:
    // a company's events reach its listeners in the order they were recorded.
    private static final String PENDING = """
            SELECT e.id, e.company_id FROM domain_event e
            WHERE e.processed_at IS NULL AND e.attempts < ?
              AND (e.next_attempt_at IS NULL OR e.next_attempt_at <= now())
              AND NOT EXISTS (SELECT 1 FROM domain_event h
                              WHERE h.company_id = e.company_id AND h.seq < e.seq AND h.processed_at IS NULL
                                AND h.attempts < ? AND h.next_attempt_at > now())
            ORDER BY e.seq
            LIMIT ?
            """;

    private record Pending(UUID id, UUID companyId) {
    }

    private record Stored(String eventType, String payload, boolean processed) {
    }

    private enum Outcome { DELIVERED, ALREADY_PROCESSED, HELD_ELSEWHERE, FAILED }

    private final SystemTransactions system;
    private final TransactionTemplate transactions;
    private final JdbcTemplate jdbc;
    private final JsonMapper json;
    private final ApplicationEventPublisher listeners;

    public DomainEventDispatcher(SystemTransactions system, PlatformTransactionManager transactionManager,
            JdbcTemplate jdbc, JsonMapper json, ApplicationEventPublisher listeners) {
        this.system = system;
        this.transactions = new TransactionTemplate(transactionManager);
        this.jdbc = jdbc;
        this.json = json;
        this.listeners = listeners;
    }

    // "-" turns the schedule off (the test profile does, and delivers by calling dispatchPending).
    @Scheduled(cron = "${app.events.dispatch-cron:*/2 * * * * *}")
    public void dispatchOnSchedule() {
        dispatchPending();
    }

    /**
     * Delivers every event that is due, and returns how many reached their listeners. Each delivery
     * opens its own transaction in the event's company, so it cannot run inside a caller's.
     */
    public int dispatchPending() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Domain events are dispatched outside any transaction");
        }
        int delivered = 0;
        while (true) {
            // every company's events, and no request to name one
            List<Pending> batch = system.run(() -> jdbc.query(PENDING,
                    (rs, i) -> new Pending(rs.getObject("id", UUID.class), rs.getObject("company_id", UUID.class)),
                    MAX_ATTEMPTS, MAX_ATTEMPTS, BATCH_SIZE));
            Set<UUID> heldBack = new HashSet<>();
            int settled = 0;
            for (Pending event : batch) {
                if (heldBack.contains(event.companyId())) {
                    continue;
                }
                switch (deliver(event)) {
                    case DELIVERED -> {
                        delivered++;
                        settled++;
                    }
                    case ALREADY_PROCESSED -> settled++;
                    case HELD_ELSEWHERE, FAILED -> heldBack.add(event.companyId());
                }
            }
            if (settled == 0 || batch.size() < BATCH_SIZE) {
                return delivered;
            }
        }
    }

    private Outcome deliver(Pending event) {
        try {
            return TenantContext.runAs(event.companyId(),
                    () -> transactions.execute(status -> deliverInTransaction(event)));
        } catch (RuntimeException ex) {
            recordFailure(event, ex);
            return Outcome.FAILED;
        }
    }

    // The listeners' writes and processed_at commit together, or neither does.
    private Outcome deliverInTransaction(Pending event) {
        List<Stored> rows = jdbc.query(
                "SELECT event_type, payload::text, processed_at FROM domain_event WHERE id = ? FOR UPDATE SKIP LOCKED",
                (rs, i) -> new Stored(rs.getString(1), rs.getString(2), rs.getTimestamp(3) != null), event.id());
        if (rows.isEmpty()) {
            return Outcome.HELD_ELSEWHERE; // another dispatcher is delivering it right now
        }
        Stored stored = rows.get(0);
        if (stored.processed()) {
            return Outcome.ALREADY_PROCESSED;
        }
        Object payload = json.readValue(stored.payload(), type(stored.eventType()));
        listeners.publishEvent(new DomainEvent<>(event.id(), event.companyId(), payload));
        jdbc.update("UPDATE domain_event SET processed_at = now() WHERE id = ?", event.id());
        return Outcome.DELIVERED;
    }

    private Class<?> type(String name) {
        try {
            return ClassUtils.forName(name, getClass().getClassLoader());
        } catch (ClassNotFoundException ex) {
            throw new IllegalStateException("Unknown domain event type " + name, ex);
        }
    }

    private void recordFailure(Pending event, RuntimeException ex) {
        Integer attempts = TenantContext.runAs(event.companyId(), () -> transactions.execute(status ->
                jdbc.queryForObject("UPDATE domain_event SET attempts = attempts + 1, last_error = ?,"
                        + " next_attempt_at = now() + (attempts + 1) * ? * interval '1 second'"
                        + " WHERE id = ? RETURNING attempts",
                        Integer.class, describe(ex), RETRY_DELAY.toSeconds(), event.id())));
        if (attempts != null && attempts >= MAX_ATTEMPTS) {
            log.error("Domain event {} failed {} times and is no longer delivered: {}", event.id(), attempts,
                    describe(ex));
        } else {
            log.warn("Domain event {} failed (attempt {}), retrying later: {}", event.id(), attempts, describe(ex));
        }
    }

    private static String describe(RuntimeException ex) {
        Throwable cause = NestedExceptionUtils.getMostSpecificCause(ex);
        String text = cause.getClass().getName() + ": " + cause.getMessage();
        return text.length() > MAX_ERROR_LENGTH ? text.substring(0, MAX_ERROR_LENGTH) : text;
    }
}
