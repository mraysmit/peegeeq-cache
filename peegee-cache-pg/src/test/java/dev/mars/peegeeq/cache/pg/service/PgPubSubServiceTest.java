package dev.mars.peegeeq.cache.pg.service;

import dev.mars.peegeeq.cache.api.model.PublishRequest;
import dev.mars.peegeeq.cache.api.model.PubSubMessage;
import dev.mars.peegeeq.cache.api.pubsub.Subscription;
import dev.mars.peegeeq.cache.pg.config.PgCacheStoreConfig;
import dev.mars.peegeeq.cache.pg.repository.PgPubSubRepository;
import dev.mars.peegeeq.cache.test.PgTestSupport;
import io.vertx.core.Promise;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Tuple;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(VertxExtension.class)
class PgPubSubServiceTest {

    private static final String SCHEMA = "peegee_cache";
    private static final String PREFIX = "peegee_cache";
    private static final String LISTENER_APPLICATION_NAME = "pgcache_pubsub_listener_test";
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
        service = new PgPubSubService(
                vertx,
                repo,
                pg.connectOptions().addProperty("application_name", LISTENER_APPLICATION_NAME),
                config);
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
    void reconnectsAndReplaysSubscriptionsAfterConnectionLoss(Vertx vertx, VertxTestContext ctx) throws Exception {
        Promise<PubSubMessage> received = Promise.promise();

        service.subscribe("reconnect-test", received::complete)
                .compose(subscription -> terminateListener())
                .compose(v -> awaitCondition(vertx, () -> !service.isListenerConnected(), 100))
                .compose(v -> awaitCondition(vertx, service::isListenerConnected, 150))
                .compose(v -> service.publish(new PublishRequest(
                        "reconnect-test", "after-reconnect", "text/plain")))
                .onFailure(ctx::failNow);

        received.future().onComplete(ctx.succeeding(message -> ctx.verify(() -> {
            assertEquals("after-reconnect", message.payload());
            ctx.completeNow();
        })));

        assertTrue(ctx.awaitCompletion(10, TimeUnit.SECONDS), "Test timed out");
    }

    @Test
    void stopCancelsPendingReconnect(Vertx vertx, VertxTestContext ctx) throws Exception {
        service.subscribe("stop-during-backoff", ignored -> {})
                .compose(subscription -> terminateListener())
                .compose(v -> awaitCondition(vertx, () -> !service.isListenerConnected(), 100))
                .compose(v -> service.stop())
                .compose(v -> delay(vertx, 1_300))
                .compose(v -> listenerConnectionCount())
                .onComplete(ctx.succeeding(count -> ctx.verify(() -> {
                    assertEquals(0L, count, "Stopping must prevent a scheduled reconnect from opening a new connection");
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

    private Future<Void> terminateListener() {
        return pool.preparedQuery("""
                        SELECT pg_terminate_backend(pid) AS terminated
                        FROM pg_stat_activity
                        WHERE application_name = $1
                          AND pid <> pg_backend_pid()
                        """)
                .execute(Tuple.of(LISTENER_APPLICATION_NAME))
                .compose(rows -> {
                    var iterator = rows.iterator();
                    if (!iterator.hasNext() || !Boolean.TRUE.equals(iterator.next().getBoolean("terminated"))) {
                        return Future.failedFuture("Listener PostgreSQL backend was not found or terminated");
                    }
                    return Future.succeededFuture();
                });
    }

    private Future<Long> listenerConnectionCount() {
        return pool.preparedQuery("""
                        SELECT COUNT(*) AS connection_count
                        FROM pg_stat_activity
                        WHERE application_name = $1
                        """)
                .execute(Tuple.of(LISTENER_APPLICATION_NAME))
                .map(rows -> rows.iterator().next().getLong("connection_count"));
    }

    private static Future<Void> awaitCondition(
            Vertx vertx, BooleanSupplier condition, int attemptsRemaining) {
        if (condition.getAsBoolean()) {
            return Future.succeededFuture();
        }
        if (attemptsRemaining == 0) {
            return Future.failedFuture("Timed out waiting for pub/sub listener state");
        }
        return delay(vertx, 25)
                .compose(v -> awaitCondition(vertx, condition, attemptsRemaining - 1));
    }

    private static Future<Void> delay(Vertx vertx, long millis) {
        return Future.future(promise -> vertx.setTimer(millis, ignored -> promise.complete()));
    }
}
