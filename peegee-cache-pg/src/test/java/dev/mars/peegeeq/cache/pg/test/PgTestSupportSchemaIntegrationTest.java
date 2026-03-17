package dev.mars.peegeeq.cache.pg.test;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.sqlclient.Pool;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

@ExtendWith(VertxExtension.class)
class PgTestSupportSchemaIntegrationTest {

    private static final String CUSTOM_SCHEMA = "test_support_custom";
    private static final PgTestSupport pg = new PgTestSupport("pg-support-schema-test", CUSTOM_SCHEMA);

    private static Pool pool;

    @BeforeAll
    static void start(Vertx vertx) throws Exception {
        pg.start(vertx);
        pool = pg.createPool(vertx);
    }

    @AfterAll
    static void stop() throws Exception {
        if (pool != null) {
            await(pool.close(), 10_000);
            pool = null;
        }
        pg.stop();
    }

    @Test
    void startCreatesConfiguredSchemaObjects() throws Exception {
        var rows = await(pool.query(
                "SELECT to_regclass('" + CUSTOM_SCHEMA + ".cache_entries') AS regclass").execute(), 10_000);
        assertEquals(CUSTOM_SCHEMA + ".cache_entries", rows.iterator().next().getString("regclass"));
    }

    private static <T> T await(Future<T> future, long timeoutMillis) throws Exception {
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

        if (!latch.await(timeoutMillis, TimeUnit.MILLISECONDS)) {
            throw new RuntimeException("Timed out waiting for Vert.x SQL operation");
        }

        Throwable error = errorRef.get();
        if (error != null) {
            throw new RuntimeException(error);
        }
        return resultRef.get();
    }
}
