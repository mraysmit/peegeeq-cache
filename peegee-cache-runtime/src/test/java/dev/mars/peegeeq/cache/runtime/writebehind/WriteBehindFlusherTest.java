package dev.mars.peegeeq.cache.runtime.writebehind;

import dev.mars.peegeeq.cache.api.cache.CacheService;
import dev.mars.peegeeq.cache.api.model.CacheEntry;
import dev.mars.peegeeq.cache.api.model.CacheKey;
import dev.mars.peegeeq.cache.api.model.CacheSetRequest;
import dev.mars.peegeeq.cache.api.model.CacheSetResult;
import dev.mars.peegeeq.cache.api.model.CacheValue;
import dev.mars.peegeeq.cache.api.model.SetMode;
import dev.mars.peegeeq.cache.api.model.TouchResult;
import dev.mars.peegeeq.cache.api.model.TtlResult;
import dev.mars.peegeeq.cache.core.writebehind.PendingWrite;
import dev.mars.peegeeq.cache.core.writebehind.WriteBehindBuffer;
import dev.mars.peegeeq.cache.core.metrics.CacheMetrics;
import dev.mars.peegeeq.cache.core.telemetry.CacheTelemetry;
import dev.mars.peegeeq.cache.runtime.config.WriteBehindConfig;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(VertxExtension.class)
class WriteBehindFlusherTest {

    @Test
    void flushDeletesBeforeSets(Vertx vertx, VertxTestContext ctx) {
        WriteBehindBuffer buffer = new WriteBehindBuffer(10);
        buffer.offer(PendingWrite.set(request("set", "value", null), 0));
        buffer.offer(PendingWrite.delete(key("delete"), 0));
        RecordingCacheService delegate = new RecordingCacheService();
        WriteBehindFlusher flusher = flusher(vertx, delegate, buffer, 10, 0);

        flusher.flush().onComplete(ctx.succeeding(ignored -> ctx.verify(() -> {
            assertEquals(List.of("deleteMany", "setMany"), delegate.calls);
            assertTrue(buffer.isEmpty());
            ctx.completeNow();
        })));
    }

    @Test
    void flushDropsElapsedTtlAndAdjustsRemainingTtl(Vertx vertx, VertxTestContext ctx) {
        WriteBehindBuffer buffer = new WriteBehindBuffer(10);
        buffer.offer(PendingWrite.set(request("expired", "old", Duration.ofSeconds(1)), 0));
        buffer.offer(PendingWrite.set(request("live", "new", Duration.ofSeconds(5)), 0));
        RecordingCacheService delegate = new RecordingCacheService();
        WriteBehindFlusher flusher = flusher(vertx, delegate, buffer, 10, Duration.ofSeconds(2).toNanos());

        flusher.flush().onComplete(ctx.succeeding(ignored -> ctx.verify(() -> {
            assertEquals(1, delegate.setRequests.size());
            assertEquals(key("live"), delegate.setRequests.getFirst().key());
            assertEquals(Duration.ofSeconds(3), delegate.setRequests.getFirst().ttl());
            ctx.completeNow();
        })));
    }

    @Test
    void flushRetriesUpToConfiguredMaximum(Vertx vertx, VertxTestContext ctx) {
        WriteBehindBuffer buffer = new WriteBehindBuffer(10);
        buffer.offer(PendingWrite.set(request("retry", "value", null), 0));
        AtomicInteger attempts = new AtomicInteger();
        RecordingCacheService delegate = new RecordingCacheService() {
            @Override
            public Future<Map<CacheKey, CacheSetResult>> setMany(List<CacheSetRequest> requests) {
                calls.add("setMany");
                if (attempts.incrementAndGet() < 3) {
                    return Future.failedFuture("planned flush failure");
                }
                setRequests.addAll(requests);
                return Future.succeededFuture(Map.of());
            }
        };
        WriteBehindFlusher flusher = flusher(vertx, delegate, buffer, 10, 0, 2);

        flusher.flush().onComplete(ctx.succeeding(ignored -> ctx.verify(() -> {
            assertEquals(3, attempts.get());
            assertTrue(buffer.isEmpty());
            ctx.completeNow();
        })));
    }

    @Test
    void drainFlushesEveryRemainingBatch(Vertx vertx, VertxTestContext ctx) {
        WriteBehindBuffer buffer = new WriteBehindBuffer(10);
        for (int i = 0; i < 5; i++) {
            buffer.offer(PendingWrite.set(request("key-" + i, "value-" + i, null), 0));
        }
        RecordingCacheService delegate = new RecordingCacheService();
        WriteBehindFlusher flusher = flusher(vertx, delegate, buffer, 2, 0);

        flusher.drain().onComplete(ctx.succeeding(ignored -> ctx.verify(() -> {
            assertEquals(3, delegate.calls.stream().filter("setMany"::equals).count());
            assertEquals(5, delegate.setRequests.size());
            assertTrue(buffer.isEmpty());
            ctx.completeNow();
        })));
    }

    @Test
    void terminalFailureRecordsOneFailedFlushAndDiscardedBatch(Vertx vertx, VertxTestContext ctx) {
        WriteBehindBuffer buffer = new WriteBehindBuffer(10);
        buffer.offer(PendingWrite.set(request("failed", "value", null), 0));
        AtomicInteger attempts = new AtomicInteger();
        RecordingCacheService delegate = new RecordingCacheService() {
            @Override
            public Future<Map<CacheKey, CacheSetResult>> setMany(List<CacheSetRequest> requests) {
                attempts.incrementAndGet();
                return Future.failedFuture("persistent failure");
            }
        };
        RecordingTelemetry telemetry = new RecordingTelemetry();
        WriteBehindConfig config = new WriteBehindConfig(
                true, Duration.ofMillis(500), 100, 10, 2, Duration.ofSeconds(5));
        WriteBehindFlusher flusher = new WriteBehindFlusher(
                vertx, delegate, buffer, config, () -> 0L, Duration.ZERO,
                new CacheMetrics(telemetry));

        flusher.flush().onComplete(ctx.failing(failure -> ctx.verify(() -> {
            assertEquals(3, attempts.get());
            assertEquals(1, telemetry.flushes.get());
            assertEquals(1, telemetry.failedFlushes.get());
            assertEquals(1, telemetry.discarded.get());
            ctx.completeNow();
        })));
    }

    private static WriteBehindFlusher flusher(
            Vertx vertx,
            CacheService delegate,
            WriteBehindBuffer buffer,
            int batchSize,
            long nowNanos) {
        return flusher(vertx, delegate, buffer, batchSize, nowNanos, 0);
    }

    private static WriteBehindFlusher flusher(
            Vertx vertx,
            CacheService delegate,
            WriteBehindBuffer buffer,
            int batchSize,
            long nowNanos,
            int maxRetries) {
        WriteBehindConfig config = new WriteBehindConfig(
                true, Duration.ofMillis(500), 100, batchSize, maxRetries, Duration.ofSeconds(5));
        return new WriteBehindFlusher(
                vertx, delegate, buffer, config, () -> nowNanos, Duration.ZERO);
    }

    private static CacheKey key(String key) {
        return new CacheKey("flusher", key);
    }

    private static CacheSetRequest request(String key, String value, Duration ttl) {
        return new CacheSetRequest(
                key(key), CacheValue.ofString(value), ttl, SetMode.UPSERT, null, false);
    }

    private static class RecordingCacheService implements CacheService {
        protected final List<String> calls = new ArrayList<>();
        protected final List<CacheSetRequest> setRequests = new ArrayList<>();

        @Override
        public Future<Map<CacheKey, CacheSetResult>> setMany(List<CacheSetRequest> requests) {
            calls.add("setMany");
            setRequests.addAll(requests);
            return Future.succeededFuture(Map.of());
        }

        @Override
        public Future<Long> deleteMany(List<CacheKey> keys) {
            calls.add("deleteMany");
            return Future.succeededFuture((long) keys.size());
        }

        @Override
        public Future<Optional<CacheEntry>> get(CacheKey key) {
            return unsupported();
        }

        @Override
        public Future<Map<CacheKey, Optional<CacheEntry>>> getMany(List<CacheKey> keys) {
            return unsupported();
        }

        @Override
        public Future<CacheSetResult> set(CacheSetRequest request) {
            return unsupported();
        }

        @Override
        public Future<Boolean> delete(CacheKey key) {
            return unsupported();
        }

        @Override
        public Future<Boolean> exists(CacheKey key) {
            return unsupported();
        }

        @Override
        public Future<TtlResult> ttl(CacheKey key) {
            return unsupported();
        }

        @Override
        public Future<Boolean> expire(CacheKey key, Duration ttl) {
            return unsupported();
        }

        @Override
        public Future<Boolean> persist(CacheKey key) {
            return unsupported();
        }

        @Override
        public Future<TouchResult> touch(CacheKey key, Duration ttl) {
            return unsupported();
        }

        private static <T> Future<T> unsupported() {
            return Future.failedFuture(new UnsupportedOperationException());
        }
    }

    private static final class RecordingTelemetry implements CacheTelemetry {
        private final AtomicInteger flushes = new AtomicInteger();
        private final AtomicInteger failedFlushes = new AtomicInteger();
        private final AtomicInteger discarded = new AtomicInteger();

        @Override
        public void recordWriteBehindFlush(int entryCount, Duration duration, Throwable failure) {
            flushes.incrementAndGet();
            if (failure != null) {
                failedFlushes.incrementAndGet();
            }
        }

        @Override
        public void recordWriteBehindDiscard(int entryCount) {
            discarded.addAndGet(entryCount);
        }
    }
}
