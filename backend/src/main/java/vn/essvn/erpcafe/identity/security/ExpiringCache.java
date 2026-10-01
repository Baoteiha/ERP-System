package vn.essvn.erpcafe.identity.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Class: ExpiringCache
 * Purpose: Keeps values per key for a fixed time, drops a key once a transaction commits, and never keeps a read that raced the drop.
 */
final class ExpiringCache<K, V> {

    private record Entry<V>(V value, Instant loadedAt) {
    }

    private final Duration ttl;
    private final Clock clock;
    private final ConcurrentMap<K, Entry<V>> entries = new ConcurrentHashMap<>();
    // bumped by every eviction: a read that started before one may hold what the evicting change replaced
    private final ConcurrentMap<K, Long> generations = new ConcurrentHashMap<>();

    ExpiringCache(Duration ttl, Clock clock) {
        this.ttl = ttl;
        this.clock = clock;
    }

    V get(K key, Function<K, V> loader) {
        Entry<V> entry = entries.get(key);
        if (entry != null && clock.instant().isBefore(entry.loadedAt().plus(ttl))) {
            return entry.value();
        }
        return reload(key, loader);
    }

    /** Reads afresh and keeps the value, unless the key was evicted while it was being read. */
    V reload(K key, Function<K, V> loader) {
        long generation = generations.getOrDefault(key, 0L);
        Instant loadedAt = clock.instant();
        V value = loader.apply(key);
        // compute holds the key's lock, which evict also takes, so no eviction slips between check and store
        entries.compute(key, (k, existing) ->
                generations.getOrDefault(k, 0L) == generation ? new Entry<>(value, loadedAt) : existing);
        return value;
    }

    /** Drops the key once the current transaction commits, or at once outside a transaction. */
    void evictAfterCommit(K key) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            evict(key);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                evict(key);
            }
        });
    }

    private void evict(K key) {
        entries.compute(key, (k, existing) -> {
            generations.merge(k, 1L, Long::sum);
            return null;
        });
    }
}
