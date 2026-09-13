package dev.mars.peegeeq.cache.benchmark;

import dev.mars.peegeeq.cache.api.cache.CacheService;
import io.vertx.core.Future;

/** Real cache SET followed by GET using deterministic B3 dataset parameters. */
public final class BenchmarkCacheSetGetOperation implements BenchmarkManagedExecution.Operation {
    private final BenchmarkCacheOperation delegate;

    public BenchmarkCacheSetGetOperation(CacheService cache, String namespace,
                                         BenchmarkScenarioParameters scenario, long seed) {
        delegate = new BenchmarkCacheOperation(cache, namespace, scenario, seed);
    }

    @Override
    public Future<Void> execute(BenchmarkManagedExecution.Attempt attempt) {
        if (!BenchmarkProductWorkloads.CACHE_SET_GET.equals(attempt.productOperation())) {
            return Future.failedFuture("SET/GET adapter cannot execute " + attempt.productOperation());
        }
        return delegate.execute(attempt);
    }
}
