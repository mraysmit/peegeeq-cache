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
            LockAcquireRequest validatedRequest = CoreValidation.requireNonNull(request, "request");
            CoreValidation.requireNonNull(validatedRequest.key(), "key");
            CoreValidation.requireNonBlank(validatedRequest.ownerToken(), "ownerToken");
            CoreValidation.requirePositiveDuration(validatedRequest.leaseTtl(), "leaseTtl");
            return wrapStoreFailure("acquire", repository.acquire(validatedRequest));
        } catch (IllegalArgumentException ex) {
            return Future.failedFuture(ex);
        }
    }

    @Override
    public Future<Boolean> renew(LockRenewRequest request) {
        try {
            LockRenewRequest validatedRequest = CoreValidation.requireNonNull(request, "request");
            CoreValidation.requireNonNull(validatedRequest.key(), "key");
            CoreValidation.requireNonBlank(validatedRequest.ownerToken(), "ownerToken");
            CoreValidation.requirePositiveDuration(validatedRequest.leaseTtl(), "leaseTtl");

            Future<Boolean> chained = repository.renew(validatedRequest)
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
            LockReleaseRequest validatedRequest = CoreValidation.requireNonNull(request, "request");
            CoreValidation.requireNonNull(validatedRequest.key(), "key");
            CoreValidation.requireNonBlank(validatedRequest.ownerToken(), "ownerToken");

            Future<Boolean> chained = repository.release(validatedRequest)
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
            LockKey validatedKey = CoreValidation.requireNonNull(key, "key");
            String validatedOwnerToken = CoreValidation.requireNonBlank(ownerToken, "ownerToken");
            Future<Boolean> chained = repository.currentLock(validatedKey)
                    .map(lock -> lock.map(state -> state.ownerToken().equals(validatedOwnerToken)).orElse(false));
            return wrapStoreFailure("isHeldBy", chained);
        } catch (IllegalArgumentException ex) {
            return Future.failedFuture(ex);
        }
    }

    @Override
    public Future<Optional<LockState>> currentLock(LockKey key) {
        try {
            LockKey validatedKey = CoreValidation.requireNonNull(key, "key");
            return wrapStoreFailure("currentLock", repository.currentLock(validatedKey));
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
