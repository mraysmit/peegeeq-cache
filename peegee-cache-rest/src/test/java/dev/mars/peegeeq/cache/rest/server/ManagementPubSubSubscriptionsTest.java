package dev.mars.peegeeq.cache.rest.server;

import dev.mars.peegeeq.cache.api.model.PublishRequest;
import dev.mars.peegeeq.cache.api.model.PubSubMessage;
import dev.mars.peegeeq.cache.api.pubsub.PubSubService;
import dev.mars.peegeeq.cache.api.pubsub.Subscription;
import dev.mars.peegeeq.cache.api.management.ManagementAuditQueueState;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManagementPubSubSubscriptionsTest {

    @Test
    void runtimeTelemetryTracksSubscriptionsAndRetainedBytesThroughCleanup() throws Exception {
        ManagementRuntimeMonitor monitor = new ManagementRuntimeMonitor();
        RecordingPubSub pubSub = new RecordingPubSub();
        ManagementPubSubSubscriptions subscriptions = new ManagementPubSubSubscriptions(
                Clock.fixed(Instant.parse("2026-08-23T12:00:00Z"), ZoneOffset.UTC),
                2, 2, 3, 32, 64, monitor);

        ManagementPubSubSubscriptions.Summary summary = await(subscriptions.create(
                "actor-a", "setup-a", "events", 2, pubSub));
        pubSub.emit("four");
        pubSub.emit("five");
        pubSub.emit("six");
        var active = monitor.snapshot(
                10, false, null, new ManagementAuditQueueState(0, 10, true));
        assertEquals(1, active.pubSubSubscriptions());
        assertEquals(7, active.retainedPayloadBytes());
        assertEquals(1, monitor.resourceSnapshot().bufferEvictions());

        await(subscriptions.delete(summary.subscriptionId(), "actor-a", "setup-a"));
        var closed = monitor.snapshot(
                10, false, null, new ManagementAuditQueueState(0, 10, true));
        assertEquals(0, closed.pubSubSubscriptions());
        assertEquals(0, closed.retainedPayloadBytes());
    }

    @Test
    void subscriptionsAreActorOwnedQuotaBoundedAndEvictOldestPayloads() throws Exception {
        RecordingPubSub pubSub = new RecordingPubSub();
        ManagementPubSubSubscriptions subscriptions = new ManagementPubSubSubscriptions(
                Clock.fixed(Instant.parse("2026-08-23T12:00:00Z"), ZoneOffset.UTC),
                1, 2, 3, 8, 16);

        ManagementPubSubSubscriptions.Summary summary = await(subscriptions.create(
                "actor-a", "setup-a", "events", 2, pubSub));
        assertEquals("events", summary.channel());
        assertEquals(2, summary.bufferLimit());
        assertEquals(Instant.parse("2026-08-23T13:00:00Z"), summary.expiresAt());

        pubSub.emit("one");
        pubSub.emit("two");
        pubSub.emit("three");
        ManagementPubSubSubscriptions.Snapshot snapshot = subscriptions.snapshot(
                summary.subscriptionId(), "actor-a", "setup-a");
        assertEquals(2, snapshot.messages().size());
        assertEquals("two", snapshot.messages().get(0).payload());
        assertEquals("three", snapshot.messages().get(1).payload());
        assertTrue(snapshot.oldestAvailableEventId() > 1);

        PubSubManagementException hidden = assertThrows(PubSubManagementException.class,
                () -> subscriptions.snapshot(summary.subscriptionId(), "actor-b", "setup-a"));
        assertEquals(PubSubManagementException.Code.NOT_FOUND, hidden.code());
        Throwable quota = failureOf(subscriptions.create(
                "actor-a", "setup-a", "other", 2, new RecordingPubSub()));
        assertEquals(PubSubManagementException.Code.LIMIT_REACHED,
                ((PubSubManagementException) quota).code());

        await(subscriptions.delete(summary.subscriptionId(), "actor-a", "setup-a"));
        assertTrue(pubSub.unsubscribed);
        assertThrows(PubSubManagementException.class, () -> subscriptions.snapshot(
                summary.subscriptionId(), "actor-a", "setup-a"));
    }

    @Test
    void expiryRemovesRetainedStateAndClosesUnderlyingSubscription() throws Exception {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-23T12:00:00Z"));
        RecordingPubSub pubSub = new RecordingPubSub();
        ManagementPubSubSubscriptions subscriptions = new ManagementPubSubSubscriptions(
                clock, 1, 2, 3, 8, 16);
        ManagementPubSubSubscriptions.Summary summary = await(subscriptions.create(
                "actor-a", "setup-a", "events", 2, pubSub));

        clock.advance(Duration.ofHours(1));

        assertEquals(0, subscriptions.size());
        assertTrue(pubSub.unsubscribed);
        assertThrows(PubSubManagementException.class, () -> subscriptions.snapshot(
                summary.subscriptionId(), "actor-a", "setup-a"));
    }

    @Test
    void retainedPayloadIsOwnerBoundAndEvictionIsReportedAsExpired() throws Exception {
        RecordingPubSub pubSub = new RecordingPubSub();
        ManagementPubSubSubscriptions subscriptions = new ManagementPubSubSubscriptions(
                Clock.fixed(Instant.parse("2026-08-23T12:00:00Z"), ZoneOffset.UTC),
                2, 2, 3, 32, 64);
        ManagementPubSubSubscriptions.Summary summary = await(subscriptions.create(
                "actor-a", "setup-a", "events", 1, pubSub));

        pubSub.emit("one");
        String evictedId = subscriptions.snapshot(
                summary.subscriptionId(), "actor-a", "setup-a").messages().getFirst().messageId();
        pubSub.emit("two");
        ManagementPubSubSubscriptions.RetainedMessage retained = subscriptions.snapshot(
                summary.subscriptionId(), "actor-a", "setup-a").messages().getFirst();

        assertEquals("two", subscriptions.retained(
                summary.subscriptionId(), "actor-a", "setup-a", retained.messageId()).payload());
        PubSubManagementException expired = assertThrows(PubSubManagementException.class,
                () -> subscriptions.retained(
                        summary.subscriptionId(), "actor-a", "setup-a", evictedId));
        assertEquals(PubSubManagementException.Code.EXPIRED, expired.code());
        PubSubManagementException hidden = assertThrows(PubSubManagementException.class,
                () -> subscriptions.retained(
                        summary.subscriptionId(), "actor-b", "setup-a", retained.messageId()));
        assertEquals(PubSubManagementException.Code.NOT_FOUND, hidden.code());
    }

    @Test
    void processByteBudgetEvictsTheGloballyOldestMessage() throws Exception {
        RecordingPubSub first = new RecordingPubSub();
        RecordingPubSub second = new RecordingPubSub();
        ManagementPubSubSubscriptions subscriptions = new ManagementPubSubSubscriptions(
                Clock.fixed(Instant.parse("2026-08-23T12:00:00Z"), ZoneOffset.UTC),
                2, 2, 3, 32, 6);
        ManagementPubSubSubscriptions.Summary firstSummary = await(subscriptions.create(
                "actor-a", "setup-a", "first", 3, first));
        ManagementPubSubSubscriptions.Summary secondSummary = await(subscriptions.create(
                "actor-a", "setup-a", "second", 3, second));

        first.emit("111");
        second.emit("222");
        first.emit("333");

        assertEquals(List.of("333"), subscriptions.snapshot(
                firstSummary.subscriptionId(), "actor-a", "setup-a").messages().stream()
                .map(ManagementPubSubSubscriptions.RetainedMessage::payload).toList());
        assertEquals(List.of("222"), subscriptions.snapshot(
                secondSummary.subscriptionId(), "actor-a", "setup-a").messages().stream()
                .map(ManagementPubSubSubscriptions.RetainedMessage::payload).toList());
    }

    @Test
    void setupAndProcessCleanupCloseEverySubscriptionAndClearBuffers() throws Exception {
        RecordingPubSub first = new RecordingPubSub();
        RecordingPubSub second = new RecordingPubSub();
        RecordingPubSub third = new RecordingPubSub();
        ManagementPubSubSubscriptions subscriptions = new ManagementPubSubSubscriptions(
                Clock.fixed(Instant.parse("2026-08-23T12:00:00Z"), ZoneOffset.UTC),
                3, 3, 4, 32, 64);
        await(subscriptions.create("actor-a", "setup-a", "first", 3, first));
        await(subscriptions.create("actor-b", "setup-a", "second", 3, second));
        await(subscriptions.create("actor-a", "setup-b", "third", 3, third));
        first.emit("retained");

        await(subscriptions.closeSetup("setup-a"));

        assertTrue(first.unsubscribed);
        assertTrue(second.unsubscribed);
        assertEquals(1, subscriptions.size());
        await(subscriptions.close());
        assertTrue(third.unsubscribed);
        assertEquals(0, subscriptions.size());
    }

    @Test
    void asyncSubscribeCompletionCannotResurrectAnExpiredState() throws Exception {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-23T12:00:00Z"));
        DeferredPubSub pubSub = new DeferredPubSub();
        ManagementPubSubSubscriptions subscriptions = new ManagementPubSubSubscriptions(
                clock, 1, 2, 3, 32, 64);

        Future<ManagementPubSubSubscriptions.Summary> creation = subscriptions.create(
                "actor-a", "setup-a", "events", 2, pubSub);
        clock.advance(Duration.ofHours(1));
        assertEquals(0, subscriptions.size());
        pubSub.complete();

        Throwable failure = failureOf(creation);
        assertEquals(PubSubManagementException.Code.EXPIRED,
                ((PubSubManagementException) failure).code());
        assertTrue(pubSub.unsubscribed);
        assertEquals(0, subscriptions.size());
    }

    @Test
    void streamOpenIsAtomicResumesAvailableMessagesAndReportsResetAfterEviction() throws Exception {
        RecordingPubSub pubSub = new RecordingPubSub();
        ManagementPubSubSubscriptions subscriptions = new ManagementPubSubSubscriptions(
                Clock.fixed(Instant.parse("2026-08-23T12:00:00Z"), ZoneOffset.UTC),
                2, 2, 3, 32, 64);
        ManagementPubSubSubscriptions.Summary summary = await(subscriptions.create(
                "actor-a", "setup-a", "events", 2, pubSub));
        pubSub.emit("one");
        pubSub.emit("two");
        long firstEventId = subscriptions.snapshot(
                summary.subscriptionId(), "actor-a", "setup-a").messages().getFirst().eventId();
        pubSub.emit("three");
        pubSub.emit("four");
        List<String> live = new java.util.concurrent.CopyOnWriteArrayList<>();
        AtomicInteger serverCloses = new AtomicInteger();

        ManagementPubSubSubscriptions.StreamOpen open = subscriptions.openStream(
                summary.subscriptionId(), "actor-a", "setup-a", firstEventId,
                message -> live.add(message.payload()), serverCloses::incrementAndGet);

        assertTrue(open.reset());
        assertEquals(firstEventId + 2, open.oldestAvailableEventId());
        assertEquals(List.of("three", "four"), open.replay().stream()
                .map(ManagementPubSubSubscriptions.RetainedMessage::payload).toList());
        pubSub.emit("five");
        assertEquals(List.of("five"), live);
        await(subscriptions.delete(summary.subscriptionId(), "actor-a", "setup-a"));
        assertEquals(1, serverCloses.get());
        open.attachment().close();
        assertEquals(1, serverCloses.get());
    }

    @Test
    void disconnectedSubscriptionExpiresAfterFiveMinutesButConnectedOneDoesNot() throws Exception {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-23T12:00:00Z"));
        RecordingPubSub disconnectedPubSub = new RecordingPubSub();
        RecordingPubSub connectedPubSub = new RecordingPubSub();
        ManagementPubSubSubscriptions subscriptions = new ManagementPubSubSubscriptions(
                clock, 2, 2, 3, 32, 64);
        ManagementPubSubSubscriptions.Summary disconnected = await(subscriptions.create(
                "actor-a", "setup-a", "disconnected", 2, disconnectedPubSub));
        ManagementPubSubSubscriptions.Summary connected = await(subscriptions.create(
                "actor-a", "setup-a", "connected", 2, connectedPubSub));
        ManagementPubSubSubscriptions.StreamOpen stream = subscriptions.openStream(
                connected.subscriptionId(), "actor-a", "setup-a", 0,
                ignored -> { }, () -> { });

        clock.advance(Duration.ofMinutes(5));

        assertEquals(1, subscriptions.size());
        assertTrue(disconnectedPubSub.unsubscribed);
        assertEquals(connected.subscriptionId(), subscriptions.snapshot(
                connected.subscriptionId(), "actor-a", "setup-a").summary().subscriptionId());
        stream.attachment().close();
        clock.advance(Duration.ofMinutes(5));
        assertEquals(0, subscriptions.size());
        assertTrue(connectedPubSub.unsubscribed);
        assertThrows(PubSubManagementException.class, () -> subscriptions.snapshot(
                disconnected.subscriptionId(), "actor-a", "setup-a"));
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get();
    }

    private static Throwable failureOf(Future<?> future) throws Exception {
        try {
            await(future);
            throw new AssertionError("operation unexpectedly succeeded");
        } catch (java.util.concurrent.ExecutionException failure) {
            return failure.getCause();
        }
    }

    private static final class RecordingPubSub implements PubSubService {
        private Consumer<PubSubMessage> handler;
        private boolean unsubscribed;

        @Override
        public Future<Integer> publish(PublishRequest request) {
            return Future.succeededFuture(1);
        }

        @Override
        public Future<Subscription> subscribe(
                String channel, Consumer<PubSubMessage> handler) {
            this.handler = handler;
            return Future.succeededFuture(new Subscription() {
                @Override
                public String channel() {
                    return channel;
                }

                @Override
                public Future<Void> unsubscribe() {
                    unsubscribed = true;
                    return Future.succeededFuture();
                }
            });
        }

        private void emit(String payload) {
            handler.accept(new PubSubMessage("events", payload, null, 1_777_777_777_777L));
        }
    }

    private static final class DeferredPubSub implements PubSubService {
        private final Promise<Subscription> subscription = Promise.promise();
        private boolean unsubscribed;

        @Override
        public Future<Integer> publish(PublishRequest request) {
            return Future.succeededFuture(1);
        }

        @Override
        public Future<Subscription> subscribe(
                String channel, Consumer<PubSubMessage> handler) {
            return subscription.future();
        }

        private void complete() {
            subscription.complete(new Subscription() {
                @Override
                public String channel() {
                    return "events";
                }

                @Override
                public Future<Void> unsubscribe() {
                    unsubscribed = true;
                    return Future.succeededFuture();
                }
            });
        }
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return Clock.fixed(now, zone);
        }

        @Override
        public Instant instant() {
            return now;
        }

        private void advance(Duration duration) {
            now = now.plus(duration);
        }
    }
}
