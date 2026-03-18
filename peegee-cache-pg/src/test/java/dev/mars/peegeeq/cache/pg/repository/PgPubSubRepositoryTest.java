package dev.mars.peegeeq.cache.pg.repository;

import dev.mars.peegeeq.cache.api.model.PublishRequest;
import dev.mars.peegeeq.cache.pg.config.PgCacheStoreConfig;
import dev.mars.peegeeq.cache.pg.test.PgTestSupport;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.pgclient.PgConnection;
import io.vertx.sqlclient.Pool;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(VertxExtension.class)
class PgPubSubRepositoryTest {

    private static final String SCHEMA = "peegee_cache";
    private static final String PREFIX = "peegee_cache";
    private static final PgTestSupport pg = new PgTestSupport("pgcache-pubsub-repo-test", SCHEMA);
    private static Pool pool;
    private static PgPubSubRepository repo;

    @BeforeAll
    static void startContainer(Vertx vertx) throws Exception {
        pg.start(vertx);
        pool = pg.createPool(vertx);
        PgCacheStoreConfig config = new PgCacheStoreConfig(SCHEMA, PREFIX);
        repo = new PgPubSubRepository(pool, config);
    }

    @AfterAll
    static void stopContainer() throws Exception {
        if (pool != null) pool.close();
        pg.stop();
    }

    // --- Publish ---

    @Test
    void publishSendsNotificationToListener(Vertx vertx, VertxTestContext ctx) throws Exception {
        String channel = "test-events";
        String qualifiedChannel = PREFIX + "__" + channel;
        String payload = "hello-world";

        // Set up a dedicated LISTEN connection to verify the notification arrives
        PgConnection.connect(vertx, pg.connectOptions())
                .onComplete(ctx.succeeding(conn -> {
                    Promise<String> received = Promise.promise();

                    conn.notificationHandler(notification -> {
                        if (qualifiedChannel.equals(notification.getChannel())) {
                            received.complete(notification.getPayload());
                        }
                    });

                    conn.query("LISTEN \"" + qualifiedChannel + "\"").execute()
                            .compose(v -> repo.publish(new PublishRequest(channel, payload, "text/plain")))
                            .compose(count -> {
                                ctx.verify(() -> assertEquals(1, count));
                                return received.future();
                            })
                            .onComplete(ctx.succeeding(receivedPayload -> {
                                ctx.verify(() -> assertEquals(payload, receivedPayload));
                                conn.close().onComplete(v -> ctx.completeNow());
                            }));
                }));

        assertTrue(ctx.awaitCompletion(10, TimeUnit.SECONDS), "Test timed out");
    }

    @Test
    void publishRejectsOversizedPayload(VertxTestContext ctx) {
        String oversized = "x".repeat(PgCacheStoreConfig.DEFAULT_MAX_PAYLOAD_BYTES + 1);
        PublishRequest request = new PublishRequest("ch", oversized, "text/plain");

        repo.publish(request).onComplete(ctx.failing(err -> ctx.verify(() -> {
            assertInstanceOf(IllegalArgumentException.class, err);
            assertTrue(err.getMessage().contains("payload"));
            ctx.completeNow();
        })));
    }

    @Test
    void publishRejectsNullChannel(VertxTestContext ctx) {
        repo.publish(new PublishRequest(null, "data", "text/plain"))
                .onComplete(ctx.failing(err -> ctx.verify(() -> {
                    assertInstanceOf(IllegalArgumentException.class, err);
                    ctx.completeNow();
                })));
    }

    @Test
    void publishRejectsBlankChannel(VertxTestContext ctx) {
        repo.publish(new PublishRequest("   ", "data", "text/plain"))
                .onComplete(ctx.failing(err -> ctx.verify(() -> {
                    assertInstanceOf(IllegalArgumentException.class, err);
                    ctx.completeNow();
                })));
    }

    @Test
    void publishRejectsNullPayload(VertxTestContext ctx) {
        repo.publish(new PublishRequest("ch", null, "text/plain"))
                .onComplete(ctx.failing(err -> ctx.verify(() -> {
                    assertInstanceOf(IllegalArgumentException.class, err);
                    ctx.completeNow();
                })));
    }

    @Test
    void publishAcceptsPayloadAtExactMaxSize(Vertx vertx, VertxTestContext ctx) throws Exception {
        String exactMax = "x".repeat(PgCacheStoreConfig.DEFAULT_MAX_PAYLOAD_BYTES);
        PublishRequest request = new PublishRequest("exact-max", exactMax, "text/plain");

        repo.publish(request).onComplete(ctx.succeeding(count -> ctx.verify(() -> {
            assertEquals(1, count);
            ctx.completeNow();
        })));
    }

    @Test
    void publishUsesConfiguredChannelPrefix(Vertx vertx, VertxTestContext ctx) throws Exception {
        String customPrefix = "custom_app";
        PgCacheStoreConfig customConfig = new PgCacheStoreConfig(SCHEMA, customPrefix);
        PgPubSubRepository customRepo = new PgPubSubRepository(pool, customConfig);

        String channel = "events";
        String qualifiedChannel = customPrefix + "__" + channel;

        PgConnection.connect(vertx, pg.connectOptions())
                .onComplete(ctx.succeeding(conn -> {
                    Promise<String> received = Promise.promise();

                    conn.notificationHandler(notification -> {
                        if (qualifiedChannel.equals(notification.getChannel())) {
                            received.complete(notification.getPayload());
                        }
                    });

                    conn.query("LISTEN \"" + qualifiedChannel + "\"").execute()
                            .compose(v -> customRepo.publish(new PublishRequest(channel, "custom-payload", "text/plain")))
                            .compose(count -> received.future())
                            .onComplete(ctx.succeeding(receivedPayload -> {
                                ctx.verify(() -> assertEquals("custom-payload", receivedPayload));
                                conn.close().onComplete(v -> ctx.completeNow());
                            }));
                }));

        assertTrue(ctx.awaitCompletion(10, TimeUnit.SECONDS), "Test timed out");
    }
}
