package dev.mars.peegeeq.cache.benchmark;

import java.util.List;

/** Stable product-operation identifiers and common mixes shared by benchmark target adapters. */
public final class BenchmarkProductWorkloads {
    public static final String CACHE_SET = "cache.set";
    public static final String CACHE_GET = "cache.get";
    public static final String CACHE_SET_GET = "cache.set-get";
    public static final String CACHE_DELETE = "cache.delete";
    public static final String COUNTER_INCREMENT = "counter.increment";
    public static final String LOCK_ACQUIRE_RELEASE = "lock.acquire-release";
    public static final String SCAN_PAGE = "cache.scan-page";

    private BenchmarkProductWorkloads() { }

    public static BenchmarkWorkloadMix setGet() {
        return new BenchmarkWorkloadMix(List.of(new BenchmarkWorkloadMix.Weight(CACHE_SET_GET, 1)));
    }

    public static BenchmarkWorkloadMix readWrite(int readWeight, int writeWeight) {
        return new BenchmarkWorkloadMix(List.of(new BenchmarkWorkloadMix.Weight(CACHE_GET, readWeight),
                new BenchmarkWorkloadMix.Weight(CACHE_SET, writeWeight)));
    }
}
