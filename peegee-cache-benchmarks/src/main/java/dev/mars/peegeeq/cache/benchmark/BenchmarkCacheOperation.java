package dev.mars.peegeeq.cache.benchmark;

import dev.mars.peegeeq.cache.api.cache.CacheService;
import dev.mars.peegeeq.cache.api.model.CacheKey;
import dev.mars.peegeeq.cache.api.model.CacheSetRequest;
import dev.mars.peegeeq.cache.api.model.CacheValue;
import dev.mars.peegeeq.cache.api.model.SetMode;
import io.vertx.core.Future;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Objects;

/** Prepared real-cache adapter for deterministic GET, SET, DELETE and SET/GET scenarios. */
public final class BenchmarkCacheOperation implements BenchmarkManagedExecution.Operation {
    private static final int PREPARATION_BATCH = 256;
    private final CacheService cache;
    private final String namespace;
    private final BenchmarkScenarioParameters scenario;
    private final long seed;

    public BenchmarkCacheOperation(CacheService cache, String namespace,
                                   BenchmarkScenarioParameters scenario, long seed) {
        this.cache = Objects.requireNonNull(cache, "cache");
        this.namespace = Objects.requireNonNull(namespace, "namespace");
        if (namespace.isBlank()) throw new IllegalArgumentException("Namespace must not be blank");
        this.scenario = Objects.requireNonNull(scenario, "scenario");
        this.seed = seed;
    }

    /** Populates the declared hit dataset in bounded batches before measured execution. */
    public Future<Void> prepareDataset() {
        return prepareFrom(0);
    }

    private Future<Void> prepareFrom(int first) {
        if (first == scenario.datasetCardinality()) return Future.succeededFuture();
        int end = Math.min(scenario.datasetCardinality(), first + PREPARATION_BATCH);
        var requests = new ArrayList<CacheSetRequest>(end - first);
        for (int index = first; index < end; index++) requests.add(setRequest(index));
        return cache.setMany(requests).compose(results -> {
            if (results.size() != requests.size()) return Future.failedFuture("Dataset preparation result is incomplete");
            return prepareFrom(end);
        });
    }

    @Override
    public Future<Void> execute(BenchmarkManagedExecution.Attempt attempt) {
        Objects.requireNonNull(attempt, "attempt");
        long index = scenario.keyIndex(seed, attempt.logicalRequestId());
        return switch (attempt.productOperation()) {
            case BenchmarkProductWorkloads.CACHE_GET -> get(attempt, index);
            case BenchmarkProductWorkloads.CACHE_SET -> cache.set(setRequest(index)).mapEmpty();
            case BenchmarkProductWorkloads.CACHE_DELETE -> cache.delete(key(index, true)).mapEmpty();
            case BenchmarkProductWorkloads.CACHE_SET_GET -> cache.set(setRequest(index))
                    .compose(ignored -> verifiedHit(key(index, true), index));
            default -> Future.failedFuture("Cache adapter cannot execute " + attempt.productOperation());
        };
    }

    private Future<Void> get(BenchmarkManagedExecution.Attempt attempt, long index) {
        boolean expectedHit = scenario.expectsHit(seed, attempt.logicalRequestId());
        var key = key(index, expectedHit);
        return cache.get(key).compose(found -> {
            if (!expectedHit) return found.isEmpty() ? Future.succeededFuture()
                    : Future.failedFuture("Expected cache miss for " + key);
            if (found.isEmpty()) return Future.failedFuture("Expected cache hit for " + key);
            return verifiedPayload(found.orElseThrow().value().binaryValue().getBytes(), index, key);
        });
    }

    private Future<Void> verifiedHit(CacheKey key, long index) {
        return cache.get(key).compose(found -> found.isEmpty() ? Future.failedFuture("SET/GET read did not find " + key)
                : verifiedPayload(found.orElseThrow().value().binaryValue().getBytes(), index, key));
    }

    private Future<Void> verifiedPayload(byte[] actual, long index, CacheKey key) {
        return Arrays.equals(scenario.payload(seed, index), actual) ? Future.succeededFuture()
                : Future.failedFuture("Cache payload mismatch for " + key);
    }

    private CacheSetRequest setRequest(long index) {
        return new CacheSetRequest(key(index, true), CacheValue.ofBytes(scenario.payload(seed, index)),
                scenario.entryTtl(), SetMode.UPSERT, null, false);
    }

    private CacheKey key(long index, boolean hit) {
        return new CacheKey(namespace, (hit ? "key-" : "miss-key-") + index);
    }
}
