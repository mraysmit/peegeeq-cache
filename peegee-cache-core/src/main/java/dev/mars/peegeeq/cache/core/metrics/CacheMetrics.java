package dev.mars.peegeeq.cache.core.metrics;

import dev.mars.peegeeq.cache.api.model.MetricsSnapshot;

import java.util.concurrent.atomic.LongAdder;

/**
 * Thread-safe in-memory cache operation metrics collector.
 * <p>
 * Uses {@link LongAdder} for high-throughput concurrent counter updates.
 */
public final class CacheMetrics {

    private final LongAdder cacheGets = new LongAdder();
    private final LongAdder cacheHits = new LongAdder();
    private final LongAdder cacheMisses = new LongAdder();
    private final LongAdder cacheSets = new LongAdder();
    private final LongAdder cacheSetsApplied = new LongAdder();
    private final LongAdder cacheDeletes = new LongAdder();
    private final LongAdder counterIncrements = new LongAdder();
    private final LongAdder counterSets = new LongAdder();
    private final LongAdder counterDeletes = new LongAdder();
    private final LongAdder lockAcquires = new LongAdder();
    private final LongAdder lockAcquiresGranted = new LongAdder();
    private final LongAdder lockRenewals = new LongAdder();
    private final LongAdder lockReleases = new LongAdder();
    private final LongAdder publishes = new LongAdder();
    private final LongAdder subscribes = new LongAdder();

    public void recordCacheGet(boolean hit) {
        cacheGets.increment();
        if (hit) {
            cacheHits.increment();
        } else {
            cacheMisses.increment();
        }
    }

    public void recordCacheSet(boolean applied) {
        cacheSets.increment();
        if (applied) {
            cacheSetsApplied.increment();
        }
    }

    public void recordCacheDelete() {
        cacheDeletes.increment();
    }

    public void recordCounterIncrement() {
        counterIncrements.increment();
    }

    public void recordCounterSet() {
        counterSets.increment();
    }

    public void recordCounterDelete() {
        counterDeletes.increment();
    }

    public void recordLockAcquire(boolean granted) {
        lockAcquires.increment();
        if (granted) {
            lockAcquiresGranted.increment();
        }
    }

    public void recordLockRenew() {
        lockRenewals.increment();
    }

    public void recordLockRelease() {
        lockReleases.increment();
    }

    public void recordPublish() {
        publishes.increment();
    }

    public void recordSubscribe() {
        subscribes.increment();
    }

    public MetricsSnapshot snapshot() {
        return new MetricsSnapshot(
                cacheGets.sum(),
                cacheHits.sum(),
                cacheMisses.sum(),
                cacheSets.sum(),
                cacheSetsApplied.sum(),
                cacheDeletes.sum(),
                counterIncrements.sum(),
                counterSets.sum(),
                counterDeletes.sum(),
                lockAcquires.sum(),
                lockAcquiresGranted.sum(),
                lockRenewals.sum(),
                lockReleases.sum(),
                publishes.sum(),
                subscribes.sum()
        );
    }

    public void reset() {
        cacheGets.reset();
        cacheHits.reset();
        cacheMisses.reset();
        cacheSets.reset();
        cacheSetsApplied.reset();
        cacheDeletes.reset();
        counterIncrements.reset();
        counterSets.reset();
        counterDeletes.reset();
        lockAcquires.reset();
        lockAcquiresGranted.reset();
        lockRenewals.reset();
        lockReleases.reset();
        publishes.reset();
        subscribes.reset();
    }
}
