package dev.mars.peegeeq.cache.runtime;

import dev.mars.peegeeq.cache.api.PeeGeeCache;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.sqlclient.Pool;

/**
 * Managed lifecycle wrapper for peegee-cache.
 * <p>
 * Callers create a manager via {@link dev.mars.peegeeq.cache.runtime.bootstrap.PeeGeeCaches},
 * then call {@link #startReactive()} to initialize and {@link #stopReactive()} to shut down.
 */
public interface PeeGeeCacheManager extends AutoCloseable {

    /**
     * Start the cache manager and any configured background components.
     *
     * @return a future that completes when startup is finished
     */
    Future<Void> startReactive();

    /**
     * Stop the cache manager and shut down background components in deterministic order.
     *
     * @return a future that completes when shutdown is finished
     */
    Future<Void> stopReactive();

    /**
     * Whether the manager has been started and has not yet been stopped.
     */
    boolean isStarted();

    /**
     * The Vert.x instance this manager is bound to.
     */
    Vertx vertx();

    /**
     * The database pool this manager is bound to.
     */
    Pool pool();

    /**
     * The cache facade providing access to all service interfaces.
     */
    PeeGeeCache cache();

    /**
     * Blocking close — delegates to {@link #stopReactive()}.
     */
    @Override
    void close();
}
