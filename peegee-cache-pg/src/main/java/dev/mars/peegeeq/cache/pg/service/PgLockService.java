package dev.mars.peegeeq.cache.pg.service;

import dev.mars.peegeeq.cache.api.exception.CacheException;
import dev.mars.peegeeq.cache.api.exception.CacheStoreException;
import dev.mars.peegeeq.cache.api.exception.LockNotHeldException;
import dev.mars.peegeeq.cache.api.lock.LockService;
import dev.mars.peegeeq.cache.api.model.LockAcquireRequest;
import dev.mars.peegeeq.cache.api.model.LockAcquireResult;
import dev.mars.peegeeq.cache.api.model.LockKey;
import dev.mars.peegeeq.cache.api.model.LockReleaseRequest;
import dev.mars.peegeeq.cache.api.model.LockRenewRequest;
import dev.mars.peegeeq.cache.api.model.LockState;
import dev.mars.peegeeq.cache.pg.repository.PgLockRepository;
import io.vertx.core.Future;

import java.util.Optional;

/**
 * Phase 4 service implementation backed by {@link PgLockRepository}.
 */
public final class PgLockService implements LockService {

    private final PgLockRepository repository;

    public PgLockService(PgLockRepository repository) {
        this.repository = repository;
    }

    @Override
    public Future<LockAcquireResult> acquire(LockAcquireRequest request) {
        if (request.leaseTtl() == null || request.leaseTtl().isZero() || request.leaseTtl().isNegative()) {
            return Future.failedFuture(new IllegalArgumentException("leaseTtl must be > 0"));
        }
        return wrapStoreFailure("acquire", repository.acquire(request));
    }

    @Override
    public Future<Boolean> renew(LockRenewRequest request) {
        if (request.leaseTtl() == null || request.leaseTtl().isZero() || request.leaseTtl().isNegative()) {
            return Future.failedFuture(new IllegalArgumentException("leaseTtl must be > 0"));
        }
        Future<Boolean> chained = repository.renew(request)
                .compose(renewed -> renewed
                        ? Future.succeededFuture(true)
                        : Future.failedFuture(new LockNotHeldException("Lock is not held by owner for renew")));
        return wrapStoreFailure("renew", chained);
    }

    @Override
    public Future<Boolean> release(LockReleaseRequest request) {
        Future<Boolean> chained = repository.release(request)
                .compose(released -> released
                        ? Future.succeededFuture(true)
                        : Future.failedFuture(new LockNotHeldException("Lock is not held by owner for release")));
        return wrapStoreFailure("release", chained);
    }

    @Override
    public Future<Boolean> isHeldBy(LockKey key, String ownerToken) {
        Future<Boolean> chained = repository.currentLock(key)
                .map(lock -> lock.map(state -> state.ownerToken().equals(ownerToken)).orElse(false));
        return wrapStoreFailure("isHeldBy", chained);
    }

    @Override
    public Future<Optional<LockState>> currentLock(LockKey key) {
        return wrapStoreFailure("currentLock", repository.currentLock(key));
    }

    private static <T> Future<T> wrapStoreFailure(String operation, Future<T> future) {
        return future.recover(err -> {
            if (err instanceof CacheException || err instanceof IllegalArgumentException) {
                return Future.failedFuture(err);
            }
            return Future.failedFuture(new CacheStoreException("Lock " + operation + " failed", err));
        });
    }
}
