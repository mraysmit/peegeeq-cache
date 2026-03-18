package dev.mars.peegeeq.cache.pg.service;

import dev.mars.peegeeq.cache.api.counter.CounterService;
import dev.mars.peegeeq.cache.api.exception.CacheException;
import dev.mars.peegeeq.cache.api.exception.CacheStoreException;
import dev.mars.peegeeq.cache.core.metrics.CacheMetrics;
import dev.mars.peegeeq.cache.api.model.CacheKey;
import dev.mars.peegeeq.cache.api.model.CounterOptions;
import dev.mars.peegeeq.cache.api.model.TtlResult;
import dev.mars.peegeeq.cache.core.validation.CoreValidation;
import dev.mars.peegeeq.cache.pg.repository.PgCounterRepository;
import io.vertx.core.Future;

import java.time.Duration;
import java.util.Optional;

/**
 * Phase 4 service implementation backed by {@link PgCounterRepository}.
 */
public final class PgCounterService implements CounterService {

    private static final CounterOptions DEFAULTS = CounterOptions.defaults();

    private final PgCounterRepository repository;
    private final CacheMetrics metrics;

    public PgCounterService(PgCounterRepository repository, CacheMetrics metrics) {
        this.repository = CoreValidation.requireNonNull(repository, "repository");
        this.metrics = CoreValidation.requireNonNull(metrics, "metrics");
    }

    @Override
    public Future<Long> increment(CacheKey key) {
        return incrementBy(key, 1L);
    }

    @Override
    public Future<Long> incrementBy(CacheKey key, long delta) {
        return wrapStoreFailure("incrementBy", repository.increment(key, delta, DEFAULTS)
                .map(result -> {
                    metrics.recordCounterIncrement();
                    return result;
                }));
    }

    @Override
    public Future<Long> decrement(CacheKey key) {
        return decrementBy(key, 1L);
    }

    @Override
    public Future<Long> decrementBy(CacheKey key, long delta) {
        if (delta < 0) {
            return Future.failedFuture(new IllegalArgumentException("delta must be >= 0"));
        }
        return wrapStoreFailure("decrementBy", repository.increment(key, -delta, DEFAULTS)
                .map(result -> {
                    metrics.recordCounterIncrement();
                    return result;
                }));
    }

    @Override
    public Future<Optional<Long>> getValue(CacheKey key) {
        return wrapStoreFailure("getValue", repository.get(key));
    }

    @Override
    public Future<Long> setValue(CacheKey key, long value, CounterOptions options) {
        try {
            CoreValidation.requireNonNull(options, "options");
            return wrapStoreFailure("setValue", repository.setValue(key, value, options)
                    .map(result -> {
                        metrics.recordCounterSet();
                        return result;
                    }));
        } catch (IllegalArgumentException ex) {
            return Future.failedFuture(ex);
        }
    }

    @Override
    public Future<TtlResult> ttl(CacheKey key) {
        return wrapStoreFailure("ttl", repository.ttl(key));
    }

    @Override
    public Future<Boolean> expire(CacheKey key, Duration ttl) {
        try {
            CoreValidation.requirePositiveDuration(ttl, "ttl");
            return wrapStoreFailure("expire", repository.expire(key, ttl));
        } catch (IllegalArgumentException ex) {
            return Future.failedFuture(ex);
        }
    }

    @Override
    public Future<Boolean> persist(CacheKey key) {
        return wrapStoreFailure("persist", repository.persist(key));
    }

    @Override
    public Future<Boolean> delete(CacheKey key) {
        return wrapStoreFailure("delete", repository.delete(key)
                .map(deleted -> {
                    if (deleted) metrics.recordCounterDelete();
                    return deleted;
                }));
    }

    private static <T> Future<T> wrapStoreFailure(String operation, Future<T> future) {
        return future.recover(err -> {
            if (err instanceof CacheException || err instanceof IllegalArgumentException) {
                return Future.failedFuture(err);
            }
            return Future.failedFuture(new CacheStoreException("Counter " + operation + " failed", err));
        });
    }
}
