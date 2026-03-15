package dev.mars.peegeeq.cache.api.lock;

import dev.mars.peegeeq.cache.api.model.LockAcquireRequest;
import dev.mars.peegeeq.cache.api.model.LockAcquireResult;
import dev.mars.peegeeq.cache.api.model.LockKey;
import dev.mars.peegeeq.cache.api.model.LockReleaseRequest;
import dev.mars.peegeeq.cache.api.model.LockRenewRequest;
import dev.mars.peegeeq.cache.api.model.LockState;
import io.vertx.core.Future;

import java.util.Optional;

public interface LockService {

    Future<LockAcquireResult> acquire(LockAcquireRequest request);

    Future<Boolean> renew(LockRenewRequest request);

    Future<Boolean> release(LockReleaseRequest request);

    Future<Boolean> isHeldBy(LockKey key, String ownerToken);

    Future<Optional<LockState>> currentLock(LockKey key);
}
