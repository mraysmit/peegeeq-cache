package dev.mars.peegeeq.cache.examples;

import dev.mars.peegeeq.cache.pg.bootstrap.BootstrapSqlRenderer;
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

    @FunctionalInterface
    interface ExampleWork {
        void run(PeeGeeCacheManager manager) throws Exception;
    }

    private static final Logger log = LoggerFactory.getLogger(ExampleRuntimeSupport.class);
    private static final Duration AWAIT_TIMEOUT = Duration.ofSeconds(20);

    private static final String POSTGRES_IMAGE = "postgres:18.3-alpine";
    private static final String DATABASE_NAME = "testdb";
    private static final String USERNAME = "test";
    private static final String PASSWORD = "test";

    private ExampleRuntimeSupport() {
    }

    

    static ExamplePostgresContainer startContainer() {
        log.info("Starting PostgreSQL Testcontainer (image: {})", POSTGRES_IMAGE);
        ExamplePostgresContainer container = new ExamplePostgresContainer(POSTGRES_IMAGE)
                .withDatabaseName(DATABASE_NAME)
                .withUsername(USERNAME)
                .withPassword(PASSWORD)
                .withReuse(false);
        try {
            container.start();
        } catch (RuntimeException ex) {
            closeQuietly(container);
            throw ex;
        }
        log.info("Container started - mapped to {}:{}", container.getHost(), container.getFirstMappedPort());
        return container;
    }

    static void stopContainer(ExamplePostgresContainer container) {
        if (container != null) {
            log.info("Stopping PostgreSQL Testcontainer");
            container.stop();
        }
    }

    static Pool createPool(Vertx vertx, ExamplePostgresContainer container) {
        PgConnectOptions connectOptions = new PgConnectOptions()
                .setHost(container.getHost())
                .setPort(container.getFirstMappedPort())
                .setDatabase(DATABASE_NAME)
                .setUser(USERNAME)
                .setPassword(PASSWORD);
        log.info("Creating Vert.x SQL pool - host={} port={} database={} user={}",
                connectOptions.getHost(), connectOptions.getPort(),
                connectOptions.getDatabase(), connectOptions.getUser());
        return Pool.pool(vertx, connectOptions, new PoolOptions().setMaxSize(8));
    }

    static void applyBootstrapSql(Vertx vertx, ExamplePostgresContainer container) throws Exception {
        applyBootstrapSql(vertx, container, BootstrapSqlRenderer.DEFAULT_SCHEMA_NAME);
    }

    static void applyBootstrapSql(Vertx vertx, ExamplePostgresContainer container, String schemaName) throws Exception {
        log.info("Applying bootstrap SQL");
        String sql = BootstrapSqlRenderer.loadForSchema(schemaName);
        PgConnectOptions opts = new PgConnectOptions()
                .setHost(container.getHost())
                .setPort(container.getFirstMappedPort())
                .setDatabase(DATABASE_NAME)
                .setUser(USERNAME)
                .setPassword(PASSWORD);
        Pool bootstrapPool = Pool.pool(vertx, opts, new PoolOptions().setMaxSize(1));
        try {
            await(bootstrapPool.query(sql).execute().mapEmpty());
            log.info("Bootstrap SQL applied successfully");
        } finally {
            await(bootstrapPool.close());
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

    static void runWithDefaultManager(Logger exampleLog, String exampleName, ExampleWork work) throws Exception {
        Vertx vertx = Vertx.vertx();
        ExamplePostgresContainer container = null;
        Pool pool = null;
        PeeGeeCacheManager manager = null;

        exampleLog.info("Starting {}", exampleName);
        try {
            container = startContainer();
            applyBootstrapSql(vertx, container);
            pool = createPool(vertx, container);
            exampleLog.info("Created PostgreSQL pool for example runtime");
            manager = startDefaultManager(vertx, pool);
            exampleLog.info("Started peegee-cache manager");
            work.run(manager);
        } catch (Exception ex) {
            exampleLog.error("{} failed: {}", exampleName, ex.getMessage());
            exampleLog.debug("{} exception stack trace", exampleName, ex);
            throw ex;
        } finally {
            exampleLog.info("Shutting down {}", exampleName);
            shutdown(manager, pool, vertx, container);
            exampleLog.info("Shutdown complete");
        }
    }

    static void shutdown(PeeGeeCacheManager manager, Pool pool, Vertx vertx,
                          ExamplePostgresContainer container) throws Exception {
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

    private static void closeQuietly(AutoCloseable closeable) {
        try {
            closeable.close();
        } catch (Exception closeError) {
            log.debug("Ignored close failure after startup error", closeError);
        }
    }
}
