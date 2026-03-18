package dev.mars.peegeeq.cache.pg.service;

import dev.mars.peegeeq.cache.api.cache.CacheService;
import dev.mars.peegeeq.cache.api.exception.CacheException;
import dev.mars.peegeeq.cache.api.exception.CacheStoreException;
import dev.mars.peegeeq.cache.core.metrics.CacheMetrics;
import dev.mars.peegeeq.cache.api.model.CacheEntry;
import dev.mars.peegeeq.cache.api.model.CacheKey;
import dev.mars.peegeeq.cache.api.model.CacheSetRequest;
import dev.mars.peegeeq.cache.api.model.CacheSetResult;
import dev.mars.peegeeq.cache.api.model.TouchResult;
import dev.mars.peegeeq.cache.api.model.TtlResult;
import dev.mars.peegeeq.cache.core.validation.CoreValidation;
import dev.mars.peegeeq.cache.pg.repository.PgCacheRepository;
import io.vertx.core.Future;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Phase 4 service implementation backed by {@link PgCacheRepository}.
 */
public final class PgCacheService implements CacheService {

    private final PgCacheRepository repository;
    private final CacheMetrics metrics;

    public PgCacheService(PgCacheRepository repository, CacheMetrics metrics) {
        this.repository = CoreValidation.requireNonNull(repository, "repository");
        this.metrics = CoreValidation.requireNonNull(metrics, "metrics");
    }

    @Override
    public Future<Optional<CacheEntry>> get(CacheKey key) {
        return wrapStoreFailure("get", repository.get(key)
                .map(entry -> {
                    metrics.recordCacheGet(entry.isPresent());
                    return entry;
                }));
    }

    @Override
    public Future<Map<CacheKey, Optional<CacheEntry>>> getMany(List<CacheKey> keys) {
        Future<Map<CacheKey, Optional<CacheEntry>>> chain = Future.succeededFuture(new LinkedHashMap<>());
        for (CacheKey key : keys) {
            chain = chain.compose(map -> repository.get(key)
                    .map(value -> {
                        metrics.recordCacheGet(value.isPresent());
                        map.put(key, value);
                        return map;
                    }));
        }
        return wrapStoreFailure("getMany", chain);
    }

    @Override
    public Future<CacheSetResult> set(CacheSetRequest request) {
        return wrapStoreFailure("set", repository.set(request)
                .map(result -> {
                    metrics.recordCacheSet(result.applied());
                    return result;
                }));
    }

    @Override
    public Future<Map<CacheKey, CacheSetResult>> setMany(List<CacheSetRequest> requests) {
        Future<Map<CacheKey, CacheSetResult>> chain = Future.succeededFuture(new LinkedHashMap<>());
        for (CacheSetRequest request : requests) {
            chain = chain.compose(map -> repository.set(request)
                    .map(result -> {
                        metrics.recordCacheSet(result.applied());
                        map.put(request.key(), result);
                        return map;
                    }));
        }
        return wrapStoreFailure("setMany", chain);
    }

    @Override
    public Future<Boolean> delete(CacheKey key) {
        return wrapStoreFailure("delete", repository.delete(key)
                .map(deleted -> {
                    if (deleted) metrics.recordCacheDelete();
                    return deleted;
                }));
    }

    @Override
    public Future<Long> deleteMany(List<CacheKey> keys) {
        Future<Long> chain = Future.succeededFuture(0L);
        for (CacheKey key : keys) {
            chain = chain.compose(count -> repository.delete(key)
                    .map(deleted -> {
                        if (deleted) metrics.recordCacheDelete();
                        return deleted ? count + 1 : count;
                    }));
        }
        return wrapStoreFailure("deleteMany", chain);
    }

    @Override
    public Future<Boolean> exists(CacheKey key) {
        return wrapStoreFailure("exists", repository.exists(key));
    }

    @Override
    public Future<TtlResult> ttl(CacheKey key) {
        return wrapStoreFailure("ttl", repository.ttl(key));
    }

    @Override
    public Future<Boolean> expire(CacheKey key, Duration ttl) {
        try {
            CoreValidation.requirePositiveDuration(ttl, "ttl");
            return wrapStoreFailure("expire", repository.expire(key, ttl.toMillis()));
        } catch (IllegalArgumentException ex) {
            return Future.failedFuture(ex);
        }
    }

    @Override
    public Future<Boolean> persist(CacheKey key) {
        return wrapStoreFailure("persist", repository.persist(key));
    }

    @Override
    public Future<TouchResult> touch(CacheKey key, Duration ttl) {
        try {
            CoreValidation.requireOptionalPositiveDuration(ttl, "ttl");
            Long ttlMillis = ttl == null ? null : ttl.toMillis();
            return wrapStoreFailure("touch", repository.touch(key, ttlMillis));
        } catch (IllegalArgumentException ex) {
            return Future.failedFuture(ex);
        }
    }

    private static <T> Future<T> wrapStoreFailure(String operation, Future<T> future) {
        return future.recover(err -> {
            if (err instanceof CacheException || err instanceof IllegalArgumentException) {
                return Future.failedFuture(err);
            }
            return Future.failedFuture(new CacheStoreException("Cache " + operation + " failed", err));
        });
    }
}