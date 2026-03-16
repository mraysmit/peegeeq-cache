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
import org.testcontainers.containers.PostgreSQLContainer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

final class ExampleRuntimeSupport {

    private static final Logger log = LoggerFactory.getLogger(ExampleRuntimeSupport.class);
    private static final Duration AWAIT_TIMEOUT = Duration.ofSeconds(20);

    private static final String POSTGRES_IMAGE = "postgres:18.3-alpine";
    private static final String DATABASE_NAME = "testdb";
    private static final String USERNAME = "test";
    private static final String PASSWORD = "test";

    private ExampleRuntimeSupport() {
    }

    static PostgreSQLContainer<?> startContainer() {
        log.info("Starting PostgreSQL Testcontainer (image: {})", POSTGRES_IMAGE);
        PostgreSQLContainer<?> container = new PostgreSQLContainer<>(POSTGRES_IMAGE)
                .withDatabaseName(DATABASE_NAME)
                .withUsername(USERNAME)
                .withPassword(PASSWORD)
                .withReuse(false);
        container.start();
        log.info("Container started — mapped to {}:{}", container.getHost(), container.getFirstMappedPort());
        return container;
    }

    static void stopContainer(PostgreSQLContainer<?> container) {
        if (container != null) {
            log.info("Stopping PostgreSQL Testcontainer");
            container.stop();
        }
    }

    static Pool createPool(Vertx vertx, PostgreSQLContainer<?> container) {
        PgConnectOptions connectOptions = new PgConnectOptions()
                .setHost(container.getHost())
                .setPort(container.getFirstMappedPort())
                .setDatabase(DATABASE_NAME)
                .setUser(USERNAME)
                .setPassword(PASSWORD);
        log.info("Creating Vert.x SQL pool — host={} port={} database={} user={}",
                connectOptions.getHost(), connectOptions.getPort(),
                connectOptions.getDatabase(), connectOptions.getUser());
        return Pool.pool(vertx, connectOptions, new PoolOptions().setMaxSize(8));
    }

    static void applyMigrations(Vertx vertx, PostgreSQLContainer<?> container) throws Exception {
        log.info("Applying database migrations");
        String sql = readClasspathResource("/db/migration/V001__create_peegee_cache_schema.sql");
        PgConnectOptions opts = new PgConnectOptions()
                .setHost(container.getHost())
                .setPort(container.getFirstMappedPort())
                .setDatabase(DATABASE_NAME)
                .setUser(USERNAME)
                .setPassword(PASSWORD);
        Pool migrationPool = Pool.pool(vertx, opts, new PoolOptions().setMaxSize(1));
        try {
            await(migrationPool.query(sql).execute().mapEmpty());
            log.info("Migrations applied successfully");
        } finally {
            await(migrationPool.close());
        }
    }

    static PeeGeeCacheManager startDefaultManager(Vertx vertx, Pool pool) throws Exception {
        log.info("Creating default peegee-cache manager");
        PeeGeeCacheManager manager = await(PeeGeeCaches.create(vertx, pool, PeeGeeCacheBootstrapOptions.defaults()));
        log.info("Starting peegee-cache manager");
        await(manager.startReactive());
        log.info("peegee-cache manager started");
        return manager;
    }

    static void shutdown(PeeGeeCacheManager manager, Pool pool, Vertx vertx,
                          PostgreSQLContainer<?> container) throws Exception {
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
        stopContainer(container);
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

    private static String readClasspathResource(String path) throws IOException {
        try (var is = ExampleRuntimeSupport.class.getResourceAsStream(path)) {
            if (is == null) {
                throw new IOException("Resource not found on classpath: " + path);
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
