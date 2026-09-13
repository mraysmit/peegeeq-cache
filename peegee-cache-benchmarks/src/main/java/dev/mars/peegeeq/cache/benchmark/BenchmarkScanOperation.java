package dev.mars.peegeeq.cache.benchmark;

import dev.mars.peegeeq.cache.api.cache.CacheService;
import dev.mars.peegeeq.cache.api.model.ScanRequest;
import dev.mars.peegeeq.cache.api.model.ScanResult;
import dev.mars.peegeeq.cache.api.scan.ScanService;
import io.vertx.core.Future;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.LongAdder;

/** Real cursor traversal adapter with completeness, uniqueness and optional value verification. */
public final class BenchmarkScanOperation implements BenchmarkManagedExecution.Operation {
    public record Statistics(long traversals, long pages, long entries, long uniqueKeys) { }

    private final ScanService scans;
    private final String namespace;
    private final BenchmarkScenarioParameters scenario;
    private final BenchmarkScanParameters parameters;
    private final long seed;
    private final BenchmarkCacheOperation preparation;
    private final LongAdder traversals = new LongAdder();
    private final LongAdder pages = new LongAdder();
    private final LongAdder entries = new LongAdder();
    private final LongAdder uniqueKeys = new LongAdder();

    public BenchmarkScanOperation(CacheService cache, ScanService scans, String namespace,
                                  BenchmarkScenarioParameters scenario,
                                  BenchmarkScanParameters parameters, long seed) {
        this.scans = Objects.requireNonNull(scans, "scans");
        this.namespace = Objects.requireNonNull(namespace, "namespace");
        if (namespace.isBlank()) throw new IllegalArgumentException("Namespace must not be blank");
        this.scenario = Objects.requireNonNull(scenario, "scenario");
        this.parameters = Objects.requireNonNull(parameters, "parameters");
        this.seed = seed;
        this.preparation = new BenchmarkCacheOperation(Objects.requireNonNull(cache, "cache"),
                namespace, scenario, seed);
    }

    public Future<Void> prepareDataset() {
        return preparation.prepareDataset();
    }

    @Override
    public Future<Void> execute(BenchmarkManagedExecution.Attempt attempt) {
        Objects.requireNonNull(attempt, "attempt");
        if (!BenchmarkProductWorkloads.SCAN_PAGE.equals(attempt.productOperation())) {
            return Future.failedFuture("Scan adapter cannot execute " + attempt.productOperation());
        }
        Set<String> expected = expectedKeys();
        return traverse(null, new HashSet<>(), new HashSet<>(), expected, 0).map(ignored -> {
            traversals.increment();
            return null;
        });
    }

    public Statistics statistics() {
        return new Statistics(traversals.sum(), pages.sum(), entries.sum(), uniqueKeys.sum());
    }

    private Future<Void> traverse(String cursor, Set<String> seenKeys, Set<String> seenCursors,
                                  Set<String> expected, int pageCount) {
        if (pageCount > expected.size() + 1) return Future.failedFuture("Scan exceeded its bounded page count");
        var request = new ScanRequest(namespace, parameters.prefix(), cursor, parameters.pageSize(),
                parameters.includeValues(), parameters.includeExpired());
        return scans.scan(request).compose(result -> verifyPage(result, seenKeys, seenCursors, expected, pageCount));
    }

    private Future<Void> verifyPage(ScanResult result, Set<String> seenKeys, Set<String> seenCursors,
                                    Set<String> expected, int pageCount) {
        pages.increment();
        entries.add(result.entries().size());
        for (var entry : result.entries()) {
            String key = entry.key().key();
            if (!seenKeys.add(key)) return Future.failedFuture("Duplicate scan key " + key);
            if (!expected.contains(key)) return Future.failedFuture("Unexpected scan key " + key);
            if (parameters.includeValues()) {
                long index;
                try { index = Long.parseLong(key.substring("key-".length())); }
                catch (RuntimeException invalid) { return Future.failedFuture("Unverifiable scan key " + key); }
                if (entry.value() == null || !Arrays.equals(scenario.payload(seed, index),
                        entry.value().binaryValue().getBytes())) {
                    return Future.failedFuture("Scan payload mismatch for " + key);
                }
            }
        }
        if (result.hasMore()) {
            if (result.nextCursor() == null || !seenCursors.add(result.nextCursor())) {
                return Future.failedFuture("Scan cursor did not advance");
            }
            return traverse(result.nextCursor(), seenKeys, seenCursors, expected, pageCount + 1);
        }
        if (result.nextCursor() != null) return Future.failedFuture("Terminal scan page retained a cursor");
        if (!seenKeys.equals(expected)) {
            var missing = new HashSet<>(expected);
            missing.removeAll(seenKeys);
            return Future.failedFuture("Scan omitted " + missing.size() + " expected keys");
        }
        uniqueKeys.add(seenKeys.size());
        return Future.succeededFuture();
    }

    private Set<String> expectedKeys() {
        var expected = new HashSet<String>();
        for (int index = 0; index < scenario.datasetCardinality(); index++) {
            String key = "key-" + index;
            if (parameters.prefix() == null || key.startsWith(parameters.prefix())) expected.add(key);
        }
        return expected;
    }
}
