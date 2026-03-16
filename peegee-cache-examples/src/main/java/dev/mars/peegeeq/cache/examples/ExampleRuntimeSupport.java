package dev.mars.peegeeq.cache.examples;

import dev.mars.peegeeq.cache.runtime.PeeGeeCacheManager;
import dev.mars.peegeeq.cache.runtime.bootstrap.PeeGeeCacheBootstrapOptions;
import dev.mars.peegeeq.cache.runtime.bootstrap.PeeGeeCaches;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

final class ExampleRuntimeSupport {

    private static final Logger log = LoggerFactory.getLogger(ExampleRuntimeSupport.class);
    private static final Duration AWAIT_TIMEOUT = Duration.ofSeconds(20);

    private ExampleRuntimeSupport() {
    }

    static PgConnectOptions connectOptionsFromEnv() {
        String host = envOrDefault("PGHOST", "127.0.0.1");
        int port = Integer.parseInt(envOrDefault("PGPORT", "5432"));
        String database = envOrDefault("PGDATABASE", "postgres");
        String user = envOrDefault("PGUSER", "postgres");
        String password = envOrDefault("PGPASSWORD", "postgres");
        log.info("Using PostgreSQL target host={} port={} database={} user={}", host, port, database, user);

        return new PgConnectOptions()
                .setHost(host)
                .setPort(port)
                .setDatabase(database)
                .setUser(user)
                .setPassword(password);
    }

    static Pool createPool(Vertx vertx) {
        log.debug("Creating Vert.x SQL pool with maxSize=8");
        return Pool.pool(vertx, connectOptionsFromEnv(), new PoolOptions().setMaxSize(8));
    }

    static PeeGeeCacheManager startDefaultManager(Vertx vertx, Pool pool) throws Exception {
        log.info("Creating default peegee-cache manager");
        PeeGeeCacheManager manager = await(PeeGeeCaches.create(vertx, pool, PeeGeeCacheBootstrapOptions.defaults()));
        log.info("Starting peegee-cache manager");
        await(manager.startReactive());
        log.info("peegee-cache manager started");
        return manager;
    }

    static void shutdown(PeeGeeCacheManager manager, Pool pool, Vertx vertx) throws Exception {
        log.info("Shutting down runtime resources");
        if (manager != null && manager.isStarted()) {
            log.debug("Stopping started manager");
            await(manager.stopReactive());
        }
        if (pool != null) {
            log.debug("Closing SQL pool");
            await(pool.close());
        }
        if (vertx != null) {
            log.debug("Closing Vert.x instance");
            await(vertx.close());
        }
        log.info("Runtime resource shutdown complete");
    }

    static <T> T await(Future<T> future) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<T> resultRef = new AtomicReference<>();
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        future.onComplete(ar -> {
            if (ar.succeeded()) {
                resultRef.set(ar.result());
            } else {
                errorRef.set(ar.cause());
            }
            latch.countDown();
        });

        if (!latch.await(AWAIT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
            log.error("Timed out waiting for async operation after {} ms", AWAIT_TIMEOUT.toMillis());
            throw new RuntimeException("Timed out waiting for async operation");
        }

        Throwable error = errorRef.get();
        if (error != null) {
            log.error("Async operation failed: {}", error.getMessage());
            log.debug("Async operation exception stack trace", error);
            throw new RuntimeException(error);
        }

        return resultRef.get();
    }

    private static String envOrDefault(String name, String fallback) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value;
    }
}
