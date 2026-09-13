package dev.mars.peegeeq.cache.benchmark;

import dev.mars.peegeeq.cache.api.counter.CounterService;
import dev.mars.peegeeq.cache.api.model.CacheKey;
import dev.mars.peegeeq.cache.api.model.CounterOptions;
import io.vertx.core.Future;

import java.util.ArrayList;
import java.util.Objects;

/** Real atomic-increment adapter with bounded preparation and post-run total verification. */
public final class BenchmarkCounterOperation implements BenchmarkManagedExecution.Operation {
    private static final int BATCH_SIZE = 256;
    private final CounterService counters;
    private final String namespace;
    private final BenchmarkCounterParameters parameters;
    private final long seed;

    public BenchmarkCounterOperation(CounterService counters, String namespace,
                                     BenchmarkCounterParameters parameters, long seed) {
        this.counters = Objects.requireNonNull(counters, "counters");
        this.namespace = Objects.requireNonNull(namespace, "namespace");
        if (namespace.isBlank()) throw new IllegalArgumentException("Namespace must not be blank");
        this.parameters = Objects.requireNonNull(parameters, "parameters");
        this.seed = seed;
    }

    public Future<Void> prepareCounters() {
        return prepareFrom(0);
    }

    private Future<Void> prepareFrom(int first) {
        if (first == parameters.counterCardinality()) return Future.succeededFuture();
        int end = Math.min(parameters.counterCardinality(), first + BATCH_SIZE);
        var writes = new ArrayList<Future<Long>>(end - first);
        for (int index = first; index < end; index++) {
            writes.add(counters.setValue(key(index), parameters.initialValue(), CounterOptions.defaults()));
        }
        return Future.join(writes).compose(ignored -> prepareFrom(end));
    }

    @Override
    public Future<Void> execute(BenchmarkManagedExecution.Attempt attempt) {
        Objects.requireNonNull(attempt, "attempt");
        if (!BenchmarkProductWorkloads.COUNTER_INCREMENT.equals(attempt.productOperation())) {
            return Future.failedFuture("Counter adapter cannot execute " + attempt.productOperation());
        }
        long index = parameters.counterIndex(seed, attempt.logicalRequestId());
        return counters.incrementBy(key(index), parameters.delta()).mapEmpty();
    }

    public Future<Void> verifyTotal(long successfulAttempts) {
        if (successfulAttempts < 0) return Future.failedFuture("Successful attempt count must not be negative");
        long expected;
        try {
            expected = Math.addExact(Math.multiplyExact(parameters.initialValue(), parameters.counterCardinality()),
                    Math.multiplyExact(parameters.delta(), successfulAttempts));
        } catch (ArithmeticException overflow) {
            return Future.failedFuture(new IllegalArgumentException("Expected counter total exceeds long range", overflow));
        }
        return sumFrom(0, 0).compose(actual -> actual == expected ? Future.succeededFuture()
                : Future.failedFuture("Counter total mismatch: expected " + expected + ", observed " + actual));
    }

    private Future<Long> sumFrom(int first, long accumulated) {
        if (first == parameters.counterCardinality()) return Future.succeededFuture(accumulated);
        int end = Math.min(parameters.counterCardinality(), first + BATCH_SIZE);
        var reads = new ArrayList<Future<java.util.Optional<Long>>>(end - first);
        for (int index = first; index < end; index++) reads.add(counters.getValue(key(index)));
        return Future.join(reads).compose(ignored -> {
            long total = accumulated;
            try {
                for (var read : reads) {
                    if (read.result().isEmpty()) return Future.failedFuture("Prepared counter is missing");
                    total = Math.addExact(total, read.result().orElseThrow());
                }
            } catch (ArithmeticException overflow) {
                return Future.failedFuture(new IllegalArgumentException("Observed counter total exceeds long range", overflow));
            }
            return sumFrom(end, total);
        });
    }

    private CacheKey key(long index) {
        return new CacheKey(namespace, "counter-" + index);
    }
}
