package dev.mars.peegeeq.cache.pg.service;

import dev.mars.peegeeq.cache.api.model.PublishRequest;
import dev.mars.peegeeq.cache.api.model.PubSubMessage;
import dev.mars.peegeeq.cache.api.pubsub.Subscription;
import dev.mars.peegeeq.cache.pg.config.PgCacheStoreConfig;
import dev.mars.peegeeq.cache.pg.repository.PgPubSubRepository;
import dev.mars.peegeeq.cache.pg.test.PgTestSupport;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.sqlclient.Pool;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(VertxExtension.class)
class PgPubSubServiceTest {

    private static final String SCHEMA = "peegee_cache";
    private static final String PREFIX = "peegee_cache";
    private static final PgTestSupport pg = new PgTestSupport("pgcache-pubsub-svc-test", SCHEMA);
    private static Pool pool;
    private static PgCacheStoreConfig config;

    private PgPubSubService service;

    @BeforeAll
    static void startContainer(Vertx vertx) throws Exception {
        pg.start(vertx);
        pool = pg.createPool(vertx);
        config = new PgCacheStoreConfig(SCHEMA, PREFIX);
    }

    @AfterAll
    static void stopContainer() throws Exception {
        if (pool != null) pool.close();
        pg.stop();
    }

    @BeforeEach
    void setUp(Vertx vertx, VertxTestContext ctx) {
        PgPubSubRepository repo = new PgPubSubRepository(pool, config);
        service = new PgPubSubService(vertx, repo, pg.connectOptions(), config);
        service.start().onComplete(ctx.succeeding(v -> ctx.completeNow()));
    }

    @AfterEach
    void tearDown(VertxTestContext ctx) {
        if (service != null) {
            service.stop().onComplete(ctx.succeeding(v -> ctx.completeNow()));
        } else {
            ctx.completeNow();
        }
    }

    // --- Publish through service ---

    @Test
    void publishReturnsOneOnSuccess(VertxTestContext ctx) {
        service.publish(new PublishRequest("ch1", "data", "text/plain"))
                .onComplete(ctx.succeeding(count -> ctx.verify(() -> {
                    assertEquals(1, count);
                    ctx.completeNow();
                })));
    }

    // --- Subscribe and receive ---

    @Test
    void subscriberReceivesPublishedMessage(Vertx vertx, VertxTestContext ctx) throws Exception {
        Promise<PubSubMessage> received = Promise.promise();

        service.subscribe("events", received::complete)
                .compose(sub -> {
                    // Small delay to ensure LISTEN is established
                    Promise<Void> delay = Promise.promise();
                    vertx.setTimer(200, id -> delay.complete());
                    return delay.future();
                })
                .compose(v -> service.publish(new PublishRequest("events", "hello", "text/plain")))
                .onComplete(ctx.succeeding(count -> {
                    // Wait for the notification to arrive
                }));

        received.future().onComplete(ctx.succeeding(msg -> ctx.verify(() -> {
            assertEquals("events", msg.channel());
            assertEquals("hello", msg.payload());
            // contentType is not transmitted through pg NOTIFY — only payload is
            assertNull(msg.contentType());
            assertTrue(msg.receivedAtEpochMillis() > 0);
            ctx.completeNow();
        })));

        assertTrue(ctx.awaitCompletion(10, TimeUnit.SECONDS), "Test timed out");
    }

    @Test
    void multipleSubscribersOnSameChannelEachReceive(Vertx vertx, VertxTestContext ctx) throws Exception {
        CopyOnWriteArrayList<String> received1 = new CopyOnWriteArrayList<>();
        CopyOnWriteArrayList<String> received2 = new CopyOnWriteArrayList<>();
        AtomicInteger totalReceived = new AtomicInteger();
        Promise<Void> bothReceived = Promise.promise();

        service.subscribe("shared", msg -> {
                    received1.add(msg.payload());
                    if (totalReceived.incrementAndGet() == 2) bothReceived.complete();
                })
                .compose(sub1 -> service.subscribe("shared", msg -> {
                    received2.add(msg.payload());
                    if (totalReceived.incrementAndGet() == 2) bothReceived.complete();
                }))
                .compose(sub2 -> {
                    Promise<Void> delay = Promise.promise();
                    vertx.setTimer(200, id -> delay.complete());
                    return delay.future();
                })
                .compose(v -> service.publish(new PublishRequest("shared", "broadcast", "text/plain")))
                .onComplete(ctx.succeeding(count -> {
                    // Wait for both subscribers to receive
                }));

        bothReceived.future().onComplete(ctx.succeeding(v -> ctx.verify(() -> {
            assertEquals(1, received1.size());
            assertEquals("broadcast", received1.get(0));
            assertEquals(1, received2.size());
            assertEquals("broadcast", received2.get(0));
            ctx.completeNow();
        })));

        assertTrue(ctx.awaitCompletion(10, TimeUnit.SECONDS), "Test timed out");
    }

    @Test
    void unsubscribeStopsDelivery(Vertx vertx, VertxTestContext ctx) throws Exception {
        CopyOnWriteArrayList<String> received = new CopyOnWriteArrayList<>();

        service.subscribe("unsub-test", msg -> received.add(msg.payload()))
                .compose(sub -> {
                    Promise<Void> delay = Promise.promise();
                    vertx.setTimer(200, id -> delay.complete());
                    return delay.future().map(sub);
                })
                .compose(sub -> service.publish(new PublishRequest("unsub-test", "before", "text/plain")).map(sub))
                .compose(sub -> {
                    // Wait for first message to arrive, then unsubscribe
                    Promise<Subscription> afterFirst = Promise.promise();
                    vertx.setTimer(500, id -> afterFirst.complete(sub));
                    return afterFirst.future();
                })
                .compose(Subscription::unsubscribe)
                .compose(v -> {
                    Promise<Void> delay = Promise.promise();
                    vertx.setTimer(200, id -> delay.complete());
                    return delay.future();
                })
                .compose(v -> service.publish(new PublishRequest("unsub-test", "after", "text/plain")))
                .compose(count -> {
                    // Wait a bit to confirm the second message is NOT received
                    Promise<Void> wait = Promise.promise();
                    vertx.setTimer(500, id -> wait.complete());
                    return wait.future();
                })
                .onComplete(ctx.succeeding(v -> ctx.verify(() -> {
                    assertEquals(1, received.size());
                    assertEquals("before", received.get(0));
                    ctx.completeNow();
                })));

        assertTrue(ctx.awaitCompletion(15, TimeUnit.SECONDS), "Test timed out");
    }

    // --- Lifecycle ---

    @Test
    void publishRejectsWhenNotStarted(Vertx vertx, VertxTestContext ctx) {
        PgPubSubRepository repo = new PgPubSubRepository(pool, config);
        PgPubSubService unstartedService = new PgPubSubService(vertx, repo, pg.connectOptions(), config);

        unstartedService.publish(new PublishRequest("ch", "data", "text/plain"))
                .onComplete(ctx.failing(err -> ctx.verify(() -> {
                    assertInstanceOf(IllegalStateException.class, err);
                    assertTrue(err.getMessage().contains("not started"));
                    ctx.completeNow();
                })));
    }

    @Test
    void subscribeRejectsWhenNotStarted(Vertx vertx, VertxTestContext ctx) {
        PgPubSubRepository repo = new PgPubSubRepository(pool, config);
        PgPubSubService unstartedService = new PgPubSubService(vertx, repo, pg.connectOptions(), config);

        unstartedService.subscribe("ch", msg -> {})
                .onComplete(ctx.failing(err -> ctx.verify(() -> {
                    assertInstanceOf(IllegalStateException.class, err);
                    assertTrue(err.getMessage().contains("not started"));
                    ctx.completeNow();
                })));
    }

    @Test
    void stopClearsSubscriptions(Vertx vertx, VertxTestContext ctx) throws Exception {
        CopyOnWriteArrayList<String> received = new CopyOnWriteArrayList<>();

        service.subscribe("stop-test", msg -> received.add(msg.payload()))
                .compose(sub -> {
                    Promise<Void> delay = Promise.promise();
                    vertx.setTimer(200, id -> delay.complete());
                    return delay.future();
                })
                .compose(v -> service.stop())
                .compose(v -> {
                    // Publish via a fresh pool query — the service is stopped
                    // but pg_notify still works; nobody should be listening
                    return pool.preparedQuery("SELECT pg_notify($1, $2)")
                            .execute(io.vertx.sqlclient.Tuple.of(PREFIX + "__stop-test", "ghost"))
                            .mapEmpty();
                })
                .compose(v -> {
                    Promise<Void> wait = Promise.promise();
                    vertx.setTimer(500, id -> wait.complete());
                    return wait.future();
                })
                .onComplete(ctx.succeeding(v -> ctx.verify(() -> {
                    assertTrue(received.isEmpty(), "Should not receive after stop");
                    // Re-nullify so tearDown doesn't double-stop
                    service = null;
                    ctx.completeNow();
                })));

        assertTrue(ctx.awaitCompletion(10, TimeUnit.SECONDS), "Test timed out");
    }

    @Test
    void subscribeRejectsNullChannel(VertxTestContext ctx) {
        service.subscribe(null, msg -> {})
                .onComplete(ctx.failing(err -> ctx.verify(() -> {
                    assertInstanceOf(IllegalArgumentException.class, err);
                    ctx.completeNow();
                })));
    }

    @Test
    void subscribeRejectsNullHandler(VertxTestContext ctx) {
        service.subscribe("ch", null)
                .onComplete(ctx.failing(err -> ctx.verify(() -> {
                    assertInstanceOf(IllegalArgumentException.class, err);
                    ctx.completeNow();
                })));
    }
}
