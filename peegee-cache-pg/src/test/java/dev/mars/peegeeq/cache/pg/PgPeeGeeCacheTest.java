package dev.mars.peegeeq.cache.pg;

import dev.mars.peegeeq.cache.api.admin.AdminService;
import dev.mars.peegeeq.cache.api.cache.CacheService;
import dev.mars.peegeeq.cache.api.counter.CounterService;
import dev.mars.peegeeq.cache.api.lock.LockService;
import dev.mars.peegeeq.cache.api.model.CacheEntry;
import dev.mars.peegeeq.cache.api.model.CacheKey;
import dev.mars.peegeeq.cache.api.model.CacheSetRequest;
import dev.mars.peegeeq.cache.api.model.CacheSetResult;
import dev.mars.peegeeq.cache.api.model.CounterOptions;
import dev.mars.peegeeq.cache.api.model.EntryStats;
import dev.mars.peegeeq.cache.api.model.LockAcquireRequest;
import dev.mars.peegeeq.cache.api.model.LockAcquireResult;
import dev.mars.peegeeq.cache.api.model.LockKey;
import dev.mars.peegeeq.cache.api.model.LockReleaseRequest;
import dev.mars.peegeeq.cache.api.model.LockRenewRequest;
import dev.mars.peegeeq.cache.api.model.LockState;
import dev.mars.peegeeq.cache.api.model.MetricsSnapshot;
import dev.mars.peegeeq.cache.api.model.PublishRequest;
import dev.mars.peegeeq.cache.api.model.PubSubMessage;
import dev.mars.peegeeq.cache.api.model.ScanRequest;
import dev.mars.peegeeq.cache.api.model.ScanResult;
import dev.mars.peegeeq.cache.api.model.TouchResult;
import dev.mars.peegeeq.cache.api.model.TtlResult;
import dev.mars.peegeeq.cache.api.pubsub.PubSubService;
import dev.mars.peegeeq.cache.api.pubsub.Subscription;
import dev.mars.peegeeq.cache.api.scan.ScanService;
import io.vertx.core.Future;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertSame;

class PgPeeGeeCacheTest {

    private static final CacheService CACHE = new NoopCacheService();
    private static final CounterService COUNTERS = new NoopCounterService();
    private static final LockService LOCKS = new NoopLockService();
    private static final ScanService SCAN = new NoopScanService();
    private static final PubSubService PUBSUB = new NoopPubSubService();
    private static final AdminService ADMIN = new NoopAdminService();

    @Test
    void facadeReturnsTheServicesItWasConstructedWith() {
        PgPeeGeeCache cache = new PgPeeGeeCache(CACHE, COUNTERS, LOCKS, SCAN, PUBSUB, ADMIN);

        assertSame(CACHE, cache.cache());
        assertSame(COUNTERS, cache.counters());
        assertSame(LOCKS, cache.locks());
        assertSame(SCAN, cache.scan());
        assertSame(PUBSUB, cache.pubSub());
        assertSame(ADMIN, cache.admin());
    }

    private static final class NoopScanService implements ScanService {
        @Override
        public Future<ScanResult> scan(ScanRequest request) {
            return Future.succeededFuture(new ScanResult(List.of(), null, false));
        }
    }

    private static final class NoopCacheService implements CacheService {
        @Override
        public Future<Optional<CacheEntry>> get(CacheKey key) {
            return Future.succeededFuture(Optional.empty());
        }

        @Override
        public Future<Map<CacheKey, Optional<CacheEntry>>> getMany(List<CacheKey> keys) {
            return Future.succeededFuture(Map.of());
        }

        @Override
        public Future<CacheSetResult> set(CacheSetRequest request) {
            return Future.failedFuture(new UnsupportedOperationException("not implemented"));
        }

        @Override
        public Future<Map<CacheKey, CacheSetResult>> setMany(List<CacheSetRequest> requests) {
            return Future.succeededFuture(Map.of());
        }

        @Override
        public Future<Boolean> delete(CacheKey key) {
            return Future.succeededFuture(false);
        }

        @Override
        public Future<Long> deleteMany(List<CacheKey> keys) {
            return Future.succeededFuture(0L);
        }

        @Override
        public Future<Boolean> exists(CacheKey key) {
            return Future.succeededFuture(false);
        }

        @Override
        public Future<TtlResult> ttl(CacheKey key) {
            return Future.succeededFuture(TtlResult.missing());
        }

        @Override
        public Future<Boolean> expire(CacheKey key, Duration ttl) {
            return Future.succeededFuture(false);
        }

        @Override
        public Future<Boolean> persist(CacheKey key) {
            return Future.succeededFuture(false);
        }

        @Override
        public Future<TouchResult> touch(CacheKey key, Duration ttl) {
            return Future.succeededFuture(new TouchResult(false, TtlResult.missing()));
        }
    }

    private static final class NoopCounterService implements CounterService {
        @Override
        public Future<Long> increment(CacheKey key) {
            return Future.succeededFuture(0L);
        }

        @Override
        public Future<Long> incrementBy(CacheKey key, long delta) {
            return Future.succeededFuture(delta);
        }

        @Override
        public Future<Long> decrement(CacheKey key) {
            return Future.succeededFuture(0L);
        }

        @Override
        public Future<Long> decrementBy(CacheKey key, long delta) {
            return Future.succeededFuture(-delta);
        }

        @Override
        public Future<Optional<Long>> getValue(CacheKey key) {
            return Future.succeededFuture(Optional.empty());
        }

        @Override
        public Future<Long> setValue(CacheKey key, long value, CounterOptions options) {
            return Future.succeededFuture(value);
        }

        @Override
        public Future<TtlResult> ttl(CacheKey key) {
            return Future.succeededFuture(TtlResult.missing());
        }

        @Override
        public Future<Boolean> expire(CacheKey key, Duration ttl) {
            return Future.succeededFuture(false);
        }

        @Override
        public Future<Boolean> persist(CacheKey key) {
            return Future.succeededFuture(false);
        }

        @Override
        public Future<Boolean> delete(CacheKey key) {
            return Future.succeededFuture(false);
        }
    }

    private static final class NoopLockService implements LockService {
        @Override
        public Future<LockAcquireResult> acquire(LockAcquireRequest request) {
            return Future.failedFuture(new UnsupportedOperationException("not implemented"));
        }

        @Override
        public Future<Boolean> renew(LockRenewRequest request) {
            return Future.succeededFuture(false);
        }

        @Override
        public Future<Boolean> release(LockReleaseRequest request) {
            return Future.succeededFuture(false);
        }

        @Override
        public Future<Boolean> isHeldBy(LockKey key, String ownerToken) {
            return Future.succeededFuture(false);
        }

        @Override
        public Future<Optional<LockState>> currentLock(LockKey key) {
            return Future.succeededFuture(Optional.empty());
        }
    }

    private static final class NoopPubSubService implements PubSubService {
        @Override
        public Future<Integer> publish(PublishRequest request) {
            return Future.succeededFuture(0);
        }

        @Override
        public Future<Subscription> subscribe(String channel, Consumer<PubSubMessage> handler) {
            return Future.failedFuture(new UnsupportedOperationException("not implemented"));
        }
    }

    private static final class NoopAdminService implements AdminService {
        @Override
        public Future<EntryStats> entryStats(String namespace) {
            return Future.succeededFuture(new EntryStats(namespace, 0, 0, 0));
        }

        @Override
        public MetricsSnapshot metrics() {
            return MetricsSnapshot.empty();
        }
    }
}
