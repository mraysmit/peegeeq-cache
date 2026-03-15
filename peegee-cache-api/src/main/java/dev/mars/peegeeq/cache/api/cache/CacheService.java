package dev.mars.peegeeq.cache.api.cache;

import dev.mars.peegeeq.cache.api.model.CacheEntry;
import dev.mars.peegeeq.cache.api.model.CacheKey;
import dev.mars.peegeeq.cache.api.model.CacheSetRequest;
import dev.mars.peegeeq.cache.api.model.CacheSetResult;
import dev.mars.peegeeq.cache.api.model.TtlResult;
import dev.mars.peegeeq.cache.api.model.TouchResult;
import io.vertx.core.Future;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface CacheService {

    Future<Optional<CacheEntry>> get(CacheKey key);

    Future<Map<CacheKey, Optional<CacheEntry>>> getMany(List<CacheKey> keys);

    Future<CacheSetResult> set(CacheSetRequest request);

    Future<Map<CacheKey, CacheSetResult>> setMany(List<CacheSetRequest> requests);

    Future<Boolean> delete(CacheKey key);

    Future<Long> deleteMany(List<CacheKey> keys);

    Future<Boolean> exists(CacheKey key);

    Future<TtlResult> ttl(CacheKey key);

    Future<Boolean> expire(CacheKey key, Duration ttl);

    Future<Boolean> persist(CacheKey key);

    Future<TouchResult> touch(CacheKey key, Duration ttl);
}
