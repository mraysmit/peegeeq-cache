package dev.mars.peegeeq.cache.core.metrics;

import dev.mars.peegeeq.cache.api.model.MetricsSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CacheMetricsTest {

    private CacheMetrics metrics;

    @BeforeEach
    void setUp() {
        metrics = new CacheMetrics();
    }

    @Test
    void freshMetricsSnapshotIsAllZeroes() {
        MetricsSnapshot snap = metrics.snapshot();
        assertEquals(MetricsSnapshot.empty(), snap);
    }

    @Test
    void cacheGetRecordsHitAndMiss() {
        metrics.recordCacheGet(true);
        metrics.recordCacheGet(true);
        metrics.recordCacheGet(false);

        MetricsSnapshot snap = metrics.snapshot();
        assertEquals(3, snap.cacheGets());
        assertEquals(2, snap.cacheHits());
        assertEquals(1, snap.cacheMisses());
    }

    @Test
    void cacheSetRecordsAppliedAndTotal() {
        metrics.recordCacheSet(true);
        metrics.recordCacheSet(false);
        metrics.recordCacheSet(true);

        MetricsSnapshot snap = metrics.snapshot();
        assertEquals(3, snap.cacheSets());
        assertEquals(2, snap.cacheSetsApplied());
    }

    @Test
    void cacheDeleteRecordsCount() {
        metrics.recordCacheDelete();
        metrics.recordCacheDelete();

        assertEquals(2, metrics.snapshot().cacheDeletes());
    }

    @Test
    void counterMetricsRecordCorrectly() {
        metrics.recordCounterIncrement();
        metrics.recordCounterIncrement();
        metrics.recordCounterSet();
        metrics.recordCounterDelete();

        MetricsSnapshot snap = metrics.snapshot();
        assertEquals(2, snap.counterIncrements());
        assertEquals(1, snap.counterSets());
        assertEquals(1, snap.counterDeletes());
    }

    @Test
    void lockMetricsRecordCorrectly() {
        metrics.recordLockAcquire(true);
        metrics.recordLockAcquire(false);
        metrics.recordLockAcquire(true);
        metrics.recordLockRenew();
        metrics.recordLockRelease();

        MetricsSnapshot snap = metrics.snapshot();
        assertEquals(3, snap.lockAcquires());
        assertEquals(2, snap.lockAcquiresGranted());
        assertEquals(1, snap.lockRenewals());
        assertEquals(1, snap.lockReleases());
    }

    @Test
    void pubSubMetricsRecordCorrectly() {
        metrics.recordPublish();
        metrics.recordPublish();
        metrics.recordSubscribe();

        MetricsSnapshot snap = metrics.snapshot();
        assertEquals(2, snap.publishes());
        assertEquals(1, snap.subscribes());
    }

    @Test
    void resetClearsAllCounters() {
        metrics.recordCacheGet(true);
        metrics.recordCacheSet(true);
        metrics.recordCacheDelete();
        metrics.recordCounterIncrement();
        metrics.recordCounterSet();
        metrics.recordCounterDelete();
        metrics.recordLockAcquire(true);
        metrics.recordLockRenew();
        metrics.recordLockRelease();
        metrics.recordPublish();
        metrics.recordSubscribe();

        metrics.reset();

        assertEquals(MetricsSnapshot.empty(), metrics.snapshot());
    }
}
