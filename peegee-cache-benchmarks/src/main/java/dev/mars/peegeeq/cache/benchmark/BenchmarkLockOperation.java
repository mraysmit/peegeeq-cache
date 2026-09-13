package dev.mars.peegeeq.cache.benchmark;

import dev.mars.peegeeq.cache.api.lock.LockService;
import dev.mars.peegeeq.cache.api.model.LockAcquireRequest;
import dev.mars.peegeeq.cache.api.model.LockKey;
import dev.mars.peegeeq.cache.api.model.LockReleaseRequest;
import io.vertx.core.Future;

import java.util.ArrayList;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Real acquire/release adapter with explicit contention and fencing observations. */
public final class BenchmarkLockOperation implements BenchmarkManagedExecution.Operation {
    private static final int VERIFY_BATCH = 256;

    public record Statistics(long acquired, long contended, long released, long fencingTokens) { }

    private final LockService locks;
    private final String namespace;
    private final BenchmarkLockParameters parameters;
    private final long seed;
    private final AtomicLong acquired = new AtomicLong();
    private final AtomicLong contended = new AtomicLong();
    private final AtomicLong released = new AtomicLong();
    private final AtomicLong fencingTokens = new AtomicLong();
    private final ConcurrentHashMap<Long, Long> lastFencingToken = new ConcurrentHashMap<>();

    public BenchmarkLockOperation(LockService locks, String namespace,
                                  BenchmarkLockParameters parameters, long seed) {
        this.locks = Objects.requireNonNull(locks, "locks");
        this.namespace = Objects.requireNonNull(namespace, "namespace");
        if (namespace.isBlank()) throw new IllegalArgumentException("Namespace must not be blank");
        this.parameters = Objects.requireNonNull(parameters, "parameters");
        this.seed = seed;
    }

    @Override
    public Future<Void> execute(BenchmarkManagedExecution.Attempt attempt) {
        Objects.requireNonNull(attempt, "attempt");
        if (!BenchmarkProductWorkloads.LOCK_ACQUIRE_RELEASE.equals(attempt.productOperation())) {
            return Future.failedFuture("Lock adapter cannot execute " + attempt.productOperation());
        }
        long index = parameters.lockIndex(seed, attempt.logicalRequestId());
        var key = key(index);
        String owner = "request-" + attempt.logicalRequestId() + "-attempt-" + attempt.attemptIndex();
        var request = new LockAcquireRequest(key, owner, parameters.leaseTtl(), false, parameters.fencing());
        return locks.acquire(request).compose(result -> {
            if (!result.acquired()) {
                contended.incrementAndGet();
                return Future.succeededFuture();
            }
            acquired.incrementAndGet();
            if (!key.equals(result.key()) || !owner.equals(result.ownerToken())) {
                return Future.failedFuture("Lock acquisition identity mismatch");
            }
            if (parameters.fencing()) {
                if (result.fencingToken() == null) return Future.failedFuture("Fencing token was not issued");
                try {
                    lastFencingToken.compute(index, (ignored, previous) -> {
                        if (previous != null && result.fencingToken() <= previous) {
                            throw new IllegalStateException("Fencing token did not increase");
                        }
                        return result.fencingToken();
                    });
                    fencingTokens.incrementAndGet();
                } catch (RuntimeException invalid) {
                    return Future.failedFuture(invalid);
                }
            } else if (result.fencingToken() != null) {
                return Future.failedFuture("Unexpected fencing token");
            }
            return locks.release(new LockReleaseRequest(key, owner)).map(ignored -> {
                released.incrementAndGet();
                return (Void) null;
            });
        });
    }

    public Statistics statistics() {
        return new Statistics(acquired.get(), contended.get(), released.get(), fencingTokens.get());
    }

    public Future<Void> verifyNoLocks() {
        return verifyFrom(0);
    }

    private Future<Void> verifyFrom(int first) {
        if (first == parameters.lockCardinality()) return Future.succeededFuture();
        int end = Math.min(parameters.lockCardinality(), first + VERIFY_BATCH);
        var reads = new ArrayList<Future<java.util.Optional<dev.mars.peegeeq.cache.api.model.LockState>>>(end - first);
        for (int index = first; index < end; index++) reads.add(locks.currentLock(key(index)));
        return Future.join(reads).compose(ignored -> {
            if (reads.stream().anyMatch(read -> read.result().isPresent())) {
                return Future.failedFuture("Benchmark-owned lock remains held");
            }
            return verifyFrom(end);
        });
    }

    private LockKey key(long index) {
        return new LockKey(namespace, "lock-" + index);
    }
}
