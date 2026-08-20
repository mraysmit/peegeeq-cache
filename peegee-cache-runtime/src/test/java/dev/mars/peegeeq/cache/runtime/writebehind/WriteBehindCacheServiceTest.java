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
import io.vertx.core.Future;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WriteBehindCacheServiceTest {

    @Test
    void writesAreAcknowledgedAfterBufferAcceptanceWithoutCallingDelegate() {
        WriteBehindBuffer buffer = new WriteBehindBuffer(10);
        RecordingCacheService delegate = new RecordingCacheService();
        WriteBehindCacheService service = new WriteBehindCacheService(delegate, buffer, () -> 100L);

        CacheSetResult setResult = service.set(request("set", "one")).result();
        Map<CacheKey, CacheSetResult> manyResult = service.setMany(List.of(
                request("set-many-one", "two"), request("set-many-two", "three"))).result();
        boolean deleteResult = service.delete(key("delete")).result();
        long deleteManyResult = service.deleteMany(List.of(
                key("delete-many-one"), key("delete-many-two"))).result();

        assertTrue(setResult.applied());
        assertEquals(0L, setResult.newVersion());
        assertEquals(2, manyResult.size());
        assertTrue(manyResult.values().stream().allMatch(CacheSetResult::applied));
        assertTrue(deleteResult);
        assertEquals(2L, deleteManyResult);
        assertTrue(delegate.calls.isEmpty());

        List<PendingWrite> writes = buffer.drain();
        assertEquals(6, writes.size());
        assertEquals(3, writes.stream().filter(w -> w.operation() == PendingWrite.Operation.SET).count());
        assertEquals(3, writes.stream().filter(w -> w.operation() == PendingWrite.Operation.DELETE).count());
        assertTrue(writes.stream().allMatch(w -> w.acceptedAtNanos() == 100L));
    }

    @Test
    void readsAndTtlMutationsBypassBufferAndReturnDelegateFutures() {
        WriteBehindBuffer buffer = new WriteBehindBuffer(10);
        RecordingCacheService delegate = new RecordingCacheService();
        WriteBehindCacheService service = new WriteBehindCacheService(delegate, buffer, () -> 100L);
        CacheKey key = key("delegated");
        Duration ttl = Duration.ofSeconds(5);

        assertSame(delegate.getResult, service.get(key));
        assertSame(delegate.getManyResult, service.getMany(List.of(key)));
        assertSame(delegate.existsResult, service.exists(key));
        assertSame(delegate.ttlResult, service.ttl(key));
        assertSame(delegate.expireResult, service.expire(key, ttl));
        assertSame(delegate.persistResult, service.persist(key));
        assertSame(delegate.touchResult, service.touch(key, ttl));

        assertEquals(List.of("get", "getMany", "exists", "ttl", "expire", "persist", "touch"), delegate.calls);
        assertTrue(buffer.isEmpty());
    }

    @Test
    void capacityOverflowFallsBackToWriteThrough() {
        WriteBehindBuffer buffer = new WriteBehindBuffer(1);
        RecordingCacheService delegate = new RecordingCacheService();
        RecordingTelemetry telemetry = new RecordingTelemetry();
        WriteBehindCacheService service = new WriteBehindCacheService(
                delegate, buffer, () -> 100L, () -> { }, new CacheMetrics(telemetry));

        service.set(request("buffered", "one"));
        Future<CacheSetResult> overflow = service.set(request("write-through", "two"));

        assertSame(delegate.setResult, overflow);
        assertEquals(List.of("set"), delegate.calls);
        assertEquals(1, buffer.size());
        assertEquals(1, telemetry.overflows.get());
    }

    private static final class RecordingTelemetry implements CacheTelemetry {
        private final AtomicInteger overflows = new AtomicInteger();

        @Override
        public void recordWriteBehindOverflow() {
            overflows.incrementAndGet();
        }
    }

    @Test
    void reachingCapacityRequestsImmediateFlush() {
        WriteBehindBuffer buffer = new WriteBehindBuffer(1);
        RecordingCacheService delegate = new RecordingCacheService();
        AtomicInteger flushRequests = new AtomicInteger();
        WriteBehindCacheService service = new WriteBehindCacheService(
                delegate, buffer, flushRequests::incrementAndGet);

        service.set(request("capacity", "value"));

        assertEquals(1, flushRequests.get());
        assertEquals(1, buffer.size());
    }

    @Test
    void shutdownGateBoundsDrainAndCanBeReopenedForRestart() {
        WriteBehindBuffer buffer = new WriteBehindBuffer(10);
        RecordingCacheService delegate = new RecordingCacheService();
        WriteBehindCacheService service = new WriteBehindCacheService(delegate, buffer, () -> 100L);

        service.stopAcceptingWrites();
        assertTrue(service.set(request("stopped", "value")).failed());
        assertTrue(buffer.isEmpty());

        service.startAcceptingWrites();
        assertTrue(service.set(request("restarted", "value")).succeeded());
        assertEquals(1, buffer.size());
    }

    private static CacheKey key(String key) {
        return new CacheKey("decorator", key);
    }

    private static CacheSetRequest request(String key, String value) {
        return new CacheSetRequest(
                key(key), CacheValue.ofString(value), null, SetMode.UPSERT, null, false);
    }

    private static final class RecordingCacheService implements CacheService {
        private final List<String> calls = new ArrayList<>();
        private final Future<Optional<CacheEntry>> getResult = Future.succeededFuture(Optional.empty());
        private final Future<Map<CacheKey, Optional<CacheEntry>>> getManyResult = Future.succeededFuture(Map.of());
        private final Future<CacheSetResult> setResult = Future.succeededFuture(new CacheSetResult(true, 7, null));
        private final Future<Map<CacheKey, CacheSetResult>> setManyResult = Future.succeededFuture(Map.of());
        private final Future<Boolean> deleteResult = Future.succeededFuture(true);
        private final Future<Long> deleteManyResult = Future.succeededFuture(1L);
        private final Future<Boolean> existsResult = Future.succeededFuture(true);
        private final Future<TtlResult> ttlResult = Future.succeededFuture(TtlResult.persistent());
        private final Future<Boolean> expireResult = Future.succeededFuture(true);
        private final Future<Boolean> persistResult = Future.succeededFuture(true);
        private final Future<TouchResult> touchResult = Future.succeededFuture(
                new TouchResult(true, TtlResult.expiring(5_000)));

        @Override
        public Future<Optional<CacheEntry>> get(CacheKey key) {
            calls.add("get");
            return getResult;
        }

        @Override
        public Future<Map<CacheKey, Optional<CacheEntry>>> getMany(List<CacheKey> keys) {
            calls.add("getMany");
            return getManyResult;
        }

        @Override
        public Future<CacheSetResult> set(CacheSetRequest request) {
            calls.add("set");
            return setResult;
        }

        @Override
        public Future<Map<CacheKey, CacheSetResult>> setMany(List<CacheSetRequest> requests) {
            calls.add("setMany");
            return setManyResult;
        }

        @Override
        public Future<Boolean> delete(CacheKey key) {
            calls.add("delete");
            return deleteResult;
        }

        @Override
        public Future<Long> deleteMany(List<CacheKey> keys) {
            calls.add("deleteMany");
            return deleteManyResult;
        }

        @Override
        public Future<Boolean> exists(CacheKey key) {
            calls.add("exists");
            return existsResult;
        }

        @Override
        public Future<TtlResult> ttl(CacheKey key) {
            calls.add("ttl");
            return ttlResult;
        }

        @Override
        public Future<Boolean> expire(CacheKey key, Duration ttl) {
            calls.add("expire");
            return expireResult;
        }

        @Override
        public Future<Boolean> persist(CacheKey key) {
            calls.add("persist");
            return persistResult;
        }

        @Override
        public Future<TouchResult> touch(CacheKey key, Duration ttl) {
            calls.add("touch");
            return touchResult;
        }
    }
}
