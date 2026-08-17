package dev.mars.peegeeq.cache.examples;

import dev.mars.peegeeq.cache.pg.bootstrap.BootstrapSqlRenderer;
import dev.mars.peegeeq.cache.runtime.PeeGeeCacheManager;
import dev.mars.peegeeq.cache.runtime.bootstrap.PeeGeeCacheBootstrapOptions;
import dev.mars.peegeeq.cache.runtime.bootstrap.PeeGeeCaches;
import dev.mars.peegeeq.cache.pg.config.PgCacheStoreConfig;
import dev.mars.peegeeq.cache.runtime.config.PeeGeeCacheConfig;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;

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

    

    static PostgreSQLContainer startContainer() {
        log.atInfo().addKeyValue("image", POSTGRES_IMAGE).log("example.postgres_container.starting");
        PostgreSQLContainer container = new PostgreSQLContainer(POSTGRES_IMAGE)
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
        log.atInfo().addKeyValue("host", container.getHost())
                .addKeyValue("port", container.getFirstMappedPort())
                .log("example.postgres_container.started");
        return container;
    }

    static void stopContainer(PostgreSQLContainer container) {
        if (container != null) {
            log.info("example.postgres_container.stopping");
            container.stop();
        }
    }

    static Pool createPool(Vertx vertx, PostgreSQLContainer container) {
        PgConnectOptions connectOptions = connectOptions(container);
        log.atInfo().addKeyValue("host", connectOptions.getHost())
                .addKeyValue("port", connectOptions.getPort())
                .log("example.sql_pool.creating");
        return Pool.pool(vertx, connectOptions, new PoolOptions().setMaxSize(8));
    }

    static PgConnectOptions connectOptions(PostgreSQLContainer container) {
        return new PgConnectOptions()
                .setHost(container.getHost())
                .setPort(container.getFirstMappedPort())
                .setDatabase(DATABASE_NAME)
                .setUser(USERNAME)
                .setPassword(PASSWORD);
    }

    static void applyBootstrapSql(Vertx vertx, PostgreSQLContainer container) throws Exception {
        applyBootstrapSql(vertx, container, BootstrapSqlRenderer.DEFAULT_SCHEMA_NAME);
    }

    static void applyBootstrapSql(Vertx vertx, PostgreSQLContainer container, String schemaName) throws Exception {
        log.info("example.schema.bootstrap_starting");
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
            log.info("example.schema.bootstrap_completed");
        } finally {
            await(bootstrapPool.close());
        }
    }

    static PeeGeeCacheManager startDefaultManager(Vertx vertx, Pool pool) throws Exception {
        log.info("example.cache_manager.creating");
        PeeGeeCacheManager manager = await(PeeGeeCaches.create(vertx, pool, PeeGeeCacheBootstrapOptions.defaults()));
        log.info("example.cache_manager.starting");
        await(manager.startReactive());
        log.info("example.cache_manager.started");
        return manager;
    }

    static PeeGeeCacheManager startConfiguredManager(
            Vertx vertx, Pool pool, PostgreSQLContainer container) throws Exception {
        PeeGeeCacheBootstrapOptions options = new PeeGeeCacheBootstrapOptions(
                PeeGeeCacheConfig.defaults(), PgCacheStoreConfig.defaults(), connectOptions(container));
        PeeGeeCacheManager manager = await(PeeGeeCaches.create(vertx, pool, options));
        await(manager.startReactive());
        return manager;
    }

    static void runWithDefaultManager(Logger exampleLog, String exampleName, ExampleWork work) throws Exception {
        runWithManager(exampleLog, exampleName, work, false);
    }

    static void runWithConfiguredManager(Logger exampleLog, String exampleName, ExampleWork work) throws Exception {
        runWithManager(exampleLog, exampleName, work, true);
    }

    private static void runWithManager(Logger exampleLog, String exampleName, ExampleWork work,
                                       boolean configurePubSub) throws Exception {
        Vertx vertx = Vertx.vertx();
        PostgreSQLContainer container = null;
        Pool pool = null;
        PeeGeeCacheManager manager = null;

        exampleLog.atInfo().addKeyValue("example", exampleName).log("example.run.starting");
        try {
            container = startContainer();
            applyBootstrapSql(vertx, container);
            pool = createPool(vertx, container);
            exampleLog.info("example.sql_pool.created");
            manager = configurePubSub
                    ? startConfiguredManager(vertx, pool, container)
                    : startDefaultManager(vertx, pool);
            exampleLog.info("example.cache_manager.ready");
            work.run(manager);
        } catch (Exception ex) {
            exampleLog.atError().addKeyValue("example", exampleName).setCause(ex).log("example.run.failed");
            throw ex;
        } finally {
            exampleLog.atInfo().addKeyValue("example", exampleName).log("example.run.stopping");
            shutdown(manager, pool, vertx, container);
            exampleLog.info("example.run.stopped");
        }
    }

    static void shutdown(PeeGeeCacheManager manager, Pool pool, Vertx vertx,
                          PostgreSQLContainer container) throws Exception {
        log.info("example.runtime.stopping");
        if (manager != null && manager.isStarted()) {
            log.debug("example.cache_manager.stopping");
            await(manager.stopReactive());
        }
        if (pool != null) {
            log.debug("example.sql_pool.closing");
            await(pool.close());
        }
        if (vertx != null) {
            log.debug("example.vertx.closing");
            await(vertx.close());
        }
        stopContainer(container);
        log.info("example.runtime.stopped");
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
            log.atError().addKeyValue("timeout.ms", AWAIT_TIMEOUT.toMillis())
                    .log("example.async_operation.timed_out");
            throw new RuntimeException("Timed out waiting for async operation");
        }

        Throwable error = errorRef.get();
        if (error != null) {
            log.atError().setCause(error).log("example.async_operation.failed");
            throw new RuntimeException(error);
        }

        return resultRef.get();
    }

    private static void closeQuietly(AutoCloseable closeable) {
        try {
            closeable.close();
        } catch (Exception closeError) {
            log.atDebug().setCause(closeError).log("example.startup_cleanup.failed");
        }
    }
}
