package dev.mars.peegeeq.cache.core.metrics;

import dev.mars.peegeeq.cache.api.model.MetricsSnapshot;
import dev.mars.peegeeq.cache.core.telemetry.CacheOperation;
import dev.mars.peegeeq.cache.core.telemetry.CacheTelemetry;
import io.vertx.core.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

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

    @Test
    void observeCompletesTelemetryForSuccessAndFailure() {
        RecordingTelemetry telemetry = new RecordingTelemetry();
        CacheMetrics observed = new CacheMetrics(telemetry);

        assertEquals("ok", observed.observe(CacheOperation.CACHE_GET,
                () -> Future.succeededFuture("ok")).result());
        RuntimeException failure = new RuntimeException("failed");
        assertSame(failure, observed.observe(CacheOperation.CACHE_SET,
                () -> Future.failedFuture(failure)).cause());

        assertEquals(2, telemetry.started);
        assertEquals(2, telemetry.completed);
        assertSame(failure, telemetry.lastFailure);
    }

    @Test
    void telemetryFailureNeverChangesCacheBehavior() {
        CacheMetrics observed = new CacheMetrics(new CacheTelemetry() {
            @Override
            public OperationSpan startOperation(CacheOperation operation) {
                throw new IllegalStateException("exporter failed");
            }
        });

        assertEquals("ok", observed.observe(CacheOperation.CACHE_GET,
                () -> Future.succeededFuture("ok")).result());
    }

    private static final class RecordingTelemetry implements CacheTelemetry {
        int started;
        int completed;
        Throwable lastFailure;

        @Override
        public OperationSpan startOperation(CacheOperation operation) {
            started++;
            return failure -> {
                completed++;
                lastFailure = failure;
            };
        }
    }
}
