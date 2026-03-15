package dev.mars.peegeeq.cache.api.counter;

import dev.mars.peegeeq.cache.api.model.CacheKey;
import dev.mars.peegeeq.cache.api.model.CounterOptions;
import dev.mars.peegeeq.cache.api.model.TtlResult;
import io.vertx.core.Future;

import java.time.Duration;
import java.util.Optional;

public interface CounterService {

    Future<Long> increment(CacheKey key);

    Future<Long> incrementBy(CacheKey key, long delta);

    Future<Long> decrement(CacheKey key);

    Future<Long> decrementBy(CacheKey key, long delta);

    Future<Optional<Long>> getValue(CacheKey key);

    Future<Long> setValue(CacheKey key, long value, CounterOptions options);

    Future<TtlResult> ttl(CacheKey key);

    Future<Boolean> expire(CacheKey key, Duration ttl);

    Future<Boolean> persist(CacheKey key);

    Future<Boolean> delete(CacheKey key);
}
