package dev.mars.peegeeq.cache.runtime.writebehind;

import dev.mars.peegeeq.cache.api.cache.CacheService;
import dev.mars.peegeeq.cache.api.model.CacheEntry;
import dev.mars.peegeeq.cache.api.model.CacheKey;
import dev.mars.peegeeq.cache.api.model.CacheSetRequest;
import dev.mars.peegeeq.cache.api.model.CacheSetResult;
import dev.mars.peegeeq.cache.api.model.TouchResult;
import dev.mars.peegeeq.cache.api.model.TtlResult;
import dev.mars.peegeeq.cache.core.metrics.CacheMetrics;
import dev.mars.peegeeq.cache.core.writebehind.PendingWrite;
import dev.mars.peegeeq.cache.core.writebehind.WriteBehindBuffer;
import io.vertx.core.Future;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.LongSupplier;

/**
 * Cache service decorator that acknowledges writes after in-memory acceptance.
 * Reads and TTL mutations deliberately bypass the buffer and use PostgreSQL state.
 */
public final class WriteBehindCacheService implements CacheService {

    private static final CacheSetResult ACCEPTED_SET = new CacheSetResult(true, 0, null);

    private final CacheService delegate;
    private final WriteBehindBuffer buffer;
    private final LongSupplier nanoTime;
    private final Runnable capacityFlush;
    private final CacheMetrics metrics;
    private final Object acceptanceLock = new Object();
    private boolean acceptingWrites = true;

    public WriteBehindCacheService(CacheService delegate, WriteBehindBuffer buffer) {
        this(delegate, buffer, System::nanoTime, () -> { }, new CacheMetrics());
    }

    public WriteBehindCacheService(
            CacheService delegate,
            WriteBehindBuffer buffer,
            Runnable capacityFlush) {
        this(delegate, buffer, System::nanoTime, capacityFlush, new CacheMetrics());
    }

    public WriteBehindCacheService(
            CacheService delegate,
            WriteBehindBuffer buffer,
            Runnable capacityFlush,
            CacheMetrics metrics) {
        this(delegate, buffer, System::nanoTime, capacityFlush, metrics);
    }

    WriteBehindCacheService(CacheService delegate, WriteBehindBuffer buffer, LongSupplier nanoTime) {
        this(delegate, buffer, nanoTime, () -> { }, new CacheMetrics());
    }

    WriteBehindCacheService(
            CacheService delegate,
            WriteBehindBuffer buffer,
            LongSupplier nanoTime,
            Runnable capacityFlush) {
        this(delegate, buffer, nanoTime, capacityFlush, new CacheMetrics());
    }

    WriteBehindCacheService(
            CacheService delegate,
            WriteBehindBuffer buffer,
            LongSupplier nanoTime,
            Runnable capacityFlush,
            CacheMetrics metrics) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.buffer = Objects.requireNonNull(buffer, "buffer");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
        this.capacityFlush = Objects.requireNonNull(capacityFlush, "capacityFlush");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    @Override
    public Future<Optional<CacheEntry>> get(CacheKey key) {
        return delegate.get(key);
    }

    @Override
    public Future<Map<CacheKey, Optional<CacheEntry>>> getMany(List<CacheKey> keys) {
        return delegate.getMany(keys);
    }

    @Override
    public Future<CacheSetResult> set(CacheSetRequest request) {
        WriteBehindBuffer.OfferResult offered;
        synchronized (acceptanceLock) {
            if (!acceptingWrites) {
                return stoppingFailure();
            }
            Objects.requireNonNull(request, "request");
            offered = buffer.offer(PendingWrite.set(request, nanoTime.getAsLong()));
        }
        recordOverflow(offered);
        requestCapacityFlush(offered);
        return offered.overflow()
                ? delegate.set(request)
                : Future.succeededFuture(ACCEPTED_SET);
    }

    @Override
    public Future<Map<CacheKey, CacheSetResult>> setMany(List<CacheSetRequest> requests) {
        Objects.requireNonNull(requests, "requests");
        Map<CacheKey, CacheSetResult> results = new LinkedHashMap<>();
        List<CacheSetRequest> overflow = new java.util.ArrayList<>();
        synchronized (acceptanceLock) {
            if (!acceptingWrites) {
                return stoppingFailure();
            }
            long acceptedAtNanos = nanoTime.getAsLong();
            for (CacheSetRequest request : requests) {
                Objects.requireNonNull(request, "requests must not contain null");
                WriteBehindBuffer.OfferResult offered = buffer.offer(PendingWrite.set(request, acceptedAtNanos));
                recordOverflow(offered);
                requestCapacityFlush(offered);
                if (offered.overflow()) {
                    overflow.add(request);
                } else {
                    results.put(request.key(), ACCEPTED_SET);
                }
            }
        }
        if (overflow.isEmpty()) {
            return Future.succeededFuture(Map.copyOf(results));
        }
        return delegate.setMany(overflow).map(applied -> {
            results.putAll(applied);
            return Map.copyOf(results);
        });
    }

    @Override
    public Future<Boolean> delete(CacheKey key) {
        WriteBehindBuffer.OfferResult offered;
        synchronized (acceptanceLock) {
            if (!acceptingWrites) {
                return stoppingFailure();
            }
            Objects.requireNonNull(key, "key");
            offered = buffer.offer(PendingWrite.delete(key, nanoTime.getAsLong()));
        }
        recordOverflow(offered);
        requestCapacityFlush(offered);
        return offered.overflow()
                ? delegate.delete(key)
                : Future.succeededFuture(true);
    }

    @Override
    public Future<Long> deleteMany(List<CacheKey> keys) {
        Objects.requireNonNull(keys, "keys");
        long accepted = 0;
        List<CacheKey> overflow = new java.util.ArrayList<>();
        synchronized (acceptanceLock) {
            if (!acceptingWrites) {
                return stoppingFailure();
            }
            long acceptedAtNanos = nanoTime.getAsLong();
            for (CacheKey key : keys) {
                Objects.requireNonNull(key, "keys must not contain null");
                WriteBehindBuffer.OfferResult offered = buffer.offer(PendingWrite.delete(key, acceptedAtNanos));
                recordOverflow(offered);
                requestCapacityFlush(offered);
                if (offered.overflow()) {
                    overflow.add(key);
                } else {
                    accepted++;
                }
            }
        }
        if (overflow.isEmpty()) {
            return Future.succeededFuture(accepted);
        }
        long acceptedCount = accepted;
        return delegate.deleteMany(overflow).map(deleted -> acceptedCount + deleted);
    }

    @Override
    public Future<Boolean> exists(CacheKey key) {
        return delegate.exists(key);
    }

    @Override
    public Future<TtlResult> ttl(CacheKey key) {
        return delegate.ttl(key);
    }

    @Override
    public Future<Boolean> expire(CacheKey key, Duration ttl) {
        return delegate.expire(key, ttl);
    }

    @Override
    public Future<Boolean> persist(CacheKey key) {
        return delegate.persist(key);
    }

    @Override
    public Future<TouchResult> touch(CacheKey key, Duration ttl) {
        return delegate.touch(key, ttl);
    }

    public void startAcceptingWrites() {
        synchronized (acceptanceLock) {
            acceptingWrites = true;
        }
    }

    public void stopAcceptingWrites() {
        synchronized (acceptanceLock) {
            acceptingWrites = false;
        }
    }

    private void requestCapacityFlush(WriteBehindBuffer.OfferResult offered) {
        if (!offered.overflow() && offered.atCapacity()) {
            capacityFlush.run();
        }
    }

    private void recordOverflow(WriteBehindBuffer.OfferResult offered) {
        if (offered.overflow()) {
            metrics.recordWriteBehindOverflow();
        }
    }

    private static <T> Future<T> stoppingFailure() {
        return Future.failedFuture(new IllegalStateException("Write-behind service is stopping"));
    }
}
