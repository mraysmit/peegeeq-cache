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
import dev.mars.peegeeq.cache.core.validation.CoreValidation;
import dev.mars.peegeeq.cache.pg.repository.PgLockRepository;
import io.vertx.core.Future;

import java.util.Optional;

/**
 * Phase 4 service implementation backed by {@link PgLockRepository}.
 */
public final class PgLockService implements LockService {

    private final PgLockRepository repository;

    public PgLockService(PgLockRepository repository) {
        this.repository = CoreValidation.requireNonNull(repository, "repository");
    }

    @Override
    public Future<LockAcquireResult> acquire(LockAcquireRequest request) {
        try {
            CoreValidation.requireNonNull(request, "request");
            CoreValidation.requireNonNull(request.key(), "key");
            CoreValidation.requireNonBlank(request.ownerToken(), "ownerToken");
            CoreValidation.requirePositiveDuration(request.leaseTtl(), "leaseTtl");
            return wrapStoreFailure("acquire", repository.acquire(request));
        } catch (IllegalArgumentException ex) {
            return Future.failedFuture(ex);
        }
    }

    @Override
    public Future<Boolean> renew(LockRenewRequest request) {
        try {
            CoreValidation.requireNonNull(request, "request");
            CoreValidation.requireNonNull(request.key(), "key");
            CoreValidation.requireNonBlank(request.ownerToken(), "ownerToken");
            CoreValidation.requirePositiveDuration(request.leaseTtl(), "leaseTtl");

            Future<Boolean> chained = repository.renew(request)
                    .compose(renewed -> renewed
                            ? Future.succeededFuture(true)
                            : Future.failedFuture(new LockNotHeldException("Lock is not held by owner for renew")));
            return wrapStoreFailure("renew", chained);
        } catch (IllegalArgumentException ex) {
            return Future.failedFuture(ex);
        }
    }

    @Override
    public Future<Boolean> release(LockReleaseRequest request) {
        try {
            CoreValidation.requireNonNull(request, "request");
            CoreValidation.requireNonNull(request.key(), "key");
            CoreValidation.requireNonBlank(request.ownerToken(), "ownerToken");

            Future<Boolean> chained = repository.release(request)
                    .compose(released -> released
                            ? Future.succeededFuture(true)
                            : Future.failedFuture(new LockNotHeldException("Lock is not held by owner for release")));
            return wrapStoreFailure("release", chained);
        } catch (IllegalArgumentException ex) {
            return Future.failedFuture(ex);
        }
    }

    @Override
    public Future<Boolean> isHeldBy(LockKey key, String ownerToken) {
        try {
            CoreValidation.requireNonNull(key, "key");
            CoreValidation.requireNonBlank(ownerToken, "ownerToken");
            Future<Boolean> chained = repository.currentLock(key)
                    .map(lock -> lock.map(state -> state.ownerToken().equals(ownerToken)).orElse(false));
            return wrapStoreFailure("isHeldBy", chained);
        } catch (IllegalArgumentException ex) {
            return Future.failedFuture(ex);
        }
    }

    @Override
    public Future<Optional<LockState>> currentLock(LockKey key) {
        try {
            CoreValidation.requireNonNull(key, "key");
            return wrapStoreFailure("currentLock", repository.currentLock(key));
        } catch (IllegalArgumentException ex) {
            return Future.failedFuture(ex);
        }
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
