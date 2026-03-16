package dev.mars.peegeeq.cache.examples;

import dev.mars.peegeeq.cache.api.model.CacheEntry;
import dev.mars.peegeeq.cache.api.model.CacheKey;
import dev.mars.peegeeq.cache.api.model.CacheSetRequest;
import dev.mars.peegeeq.cache.api.model.CacheSetResult;
import dev.mars.peegeeq.cache.api.model.CacheValue;
import dev.mars.peegeeq.cache.api.model.CounterOptions;
import dev.mars.peegeeq.cache.api.model.LockAcquireRequest;
import dev.mars.peegeeq.cache.api.model.LockAcquireResult;
import dev.mars.peegeeq.cache.api.model.LockKey;
import dev.mars.peegeeq.cache.api.model.LockReleaseRequest;
import dev.mars.peegeeq.cache.api.model.SetMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Optional;

/**
 * Minimal integration example that boots peegee-cache and exercises
 * cache, counter, and lock primitives against PostgreSQL.
 */
public final class BasicIntegrationExample {

    private static final Logger log = LoggerFactory.getLogger(BasicIntegrationExample.class);

    private BasicIntegrationExample() {
    }

    public static void main(String[] args) throws Exception {
        ExampleRuntimeSupport.runWithDefaultManager(log, "BasicIntegrationExample", manager -> {
            var cache = manager.cache();

            CacheKey cacheKey = new CacheKey("examples", "hello");
            CacheSetRequest setRequest = new CacheSetRequest(
                    cacheKey,
                    CacheValue.ofString("world"),
                    Duration.ofMinutes(5),
                    SetMode.UPSERT,
                    null,
                    false
            );

                CacheSetResult setResult = ExampleLogSupport.timed(log, "cache.set", () -> ExampleRuntimeSupport.await(cache.cache().set(setRequest)));
                Optional<CacheEntry> loaded = ExampleLogSupport.timed(log, "cache.get", () -> ExampleRuntimeSupport.await(cache.cache().get(cacheKey)));

            CacheKey counterKey = new CacheKey("examples", "requests");
            long counterValue = ExampleLogSupport.timed(log, "counter.incrementBy", () -> ExampleRuntimeSupport.await(cache.counters().incrementBy(counterKey, 1)));
            long resetCounter = ExampleLogSupport.timed(log, "counter.setValue", () -> ExampleRuntimeSupport.await(cache.counters().setValue(counterKey, 10, CounterOptions.defaults())));

            LockKey lockKey = new LockKey("examples", "startup-lock");
            String ownerToken = "example-app";

            LockAcquireResult lock = ExampleLogSupport.timed(log, "lock.acquire", () ->
                ExampleRuntimeSupport.await(cache.locks().acquire(new LockAcquireRequest(
                    lockKey,
                    ownerToken,
                    Duration.ofSeconds(20),
                    false,
                    true
                ))));

            ExampleLogSupport.logData(log, "cache-set result",
                "key", cacheKey.asQualifiedKey(),
                "applied", setResult.applied(),
                "version", setResult.newVersion());

            ExampleLogSupport.logData(log, "cache-get result",
                "key", cacheKey.asQualifiedKey(),
                "value", loaded.map(entry -> entry.value().asString()).orElse("<missing>"));

            ExampleLogSupport.logData(log, "counter results",
                "key", counterKey.asQualifiedKey(),
                "incremented", counterValue,
                "resetTo", resetCounter);

            ExampleLogSupport.logData(log, "lock acquire result",
                "key", lockKey.asQualifiedKey(),
                "acquired", lock.acquired(),
                "fencingToken", lock.fencingToken());

            if (lock.acquired()) {
                boolean released = ExampleLogSupport.timed(log, "lock.release", () -> ExampleRuntimeSupport.await(cache.locks().release(new LockReleaseRequest(lockKey, ownerToken))));
                ExampleLogSupport.logData(log, "lock release result", "key", lockKey.asQualifiedKey(), "released", released);
            }
        });
    }
}
