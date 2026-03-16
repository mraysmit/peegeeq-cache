package dev.mars.peegeeq.cache.runtime.bootstrap;

import dev.mars.peegeeq.cache.runtime.PeeGeeCacheManager;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.sqlclient.Pool;

/**
 * Factory for creating managed peegee-cache runtime instances.
 */
interface PeeGeeCacheFactory {

    Future<PeeGeeCacheManager> createManager(
            Vertx vertx,
            Pool pool,
            PeeGeeCacheBootstrapOptions options
    );
}
