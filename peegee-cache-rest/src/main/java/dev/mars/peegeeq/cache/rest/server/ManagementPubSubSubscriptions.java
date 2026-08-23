package dev.mars.peegeeq.cache.rest.server;

import dev.mars.peegeeq.cache.api.model.PubSubMessage;
import dev.mars.peegeeq.cache.api.pubsub.PubSubService;
import dev.mars.peegeeq.cache.api.pubsub.Subscription;
import io.vertx.core.Future;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Bounded process-local actor-owned pub/sub subscription and retained-payload store. */
public final class ManagementPubSubSubscriptions implements SetupScopeLifecycle {

    private static final Logger LOGGER = LoggerFactory.getLogger(ManagementPubSubSubscriptions.class);
    private static final Duration LIFETIME = Duration.ofHours(1);
    private static final Duration DISCONNECTED_LIFETIME = Duration.ofMinutes(5);

    private final Clock clock;
    private final int maximumPerActor;
    private final int maximumPerSetup;
    private final int maximumProcess;
    private final long maximumBytesPerSubscription;
    private final long maximumProcessBytes;
    private final ManagementRuntimeMonitor runtimeMonitor;
    private final Map<String, State> states = new HashMap<>();
    private long processBytes;
    private long nextArrivalSequence = 1;

    public ManagementPubSubSubscriptions(
            Clock clock,
            int maximumPerActor,
            int maximumPerSetup,
            int maximumProcess,
            long maximumBytesPerSubscription,
            long maximumProcessBytes) {
        this(clock, maximumPerActor, maximumPerSetup, maximumProcess,
                maximumBytesPerSubscription, maximumProcessBytes, null);
    }

    public ManagementPubSubSubscriptions(
            Clock clock,
            int maximumPerActor,
            int maximumPerSetup,
            int maximumProcess,
            long maximumBytesPerSubscription,
            long maximumProcessBytes,
            ManagementRuntimeMonitor runtimeMonitor) {
        this.clock = Objects.requireNonNull(clock, "clock");
        if (maximumPerActor < 1 || maximumPerSetup < 1 || maximumProcess < 1
                || maximumBytesPerSubscription < 1 || maximumProcessBytes < 1) {
            throw new IllegalArgumentException("subscription limits must be positive");
        }
        this.maximumPerActor = maximumPerActor;
        this.maximumPerSetup = maximumPerSetup;
        this.maximumProcess = maximumProcess;
        this.maximumBytesPerSubscription = maximumBytesPerSubscription;
        this.maximumProcessBytes = maximumProcessBytes;
        this.runtimeMonitor = runtimeMonitor;
        refreshTelemetry();
    }

    public Future<Summary> create(
            String actor,
            String setupId,
            String channel,
            int bufferLimit,
            PubSubService pubSub) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(setupId, "setupId");
        Objects.requireNonNull(channel, "channel");
        Objects.requireNonNull(pubSub, "pubSub");
        if (bufferLimit < 1 || bufferLimit > 500) {
            return Future.failedFuture(new IllegalArgumentException(
                    "bufferLimit must be between 1 and 500"));
        }
        Instant createdAt = clock.instant();
        String id = UUID.randomUUID().toString();
        State state = new State(
                id, actor, setupId, channel, bufferLimit,
                createdAt, createdAt.plus(LIFETIME));
        synchronized (this) {
            purgeExpired();
            long actorCount = states.values().stream().filter(existing ->
                    existing.actor.equals(actor) && existing.setupId.equals(setupId)).count();
            long setupCount = states.values().stream().filter(existing ->
                    existing.setupId.equals(setupId)).count();
            if (actorCount >= maximumPerActor || setupCount >= maximumPerSetup
                    || states.size() >= maximumProcess) {
                return Future.failedFuture(new PubSubManagementException(
                        PubSubManagementException.Code.LIMIT_REACHED));
            }
            states.put(id, state);
            refreshTelemetry();
        }
        Future<Subscription> operation;
        try {
            operation = pubSub.subscribe(channel, message -> retain(state, message));
        } catch (RuntimeException failure) {
            removeState(state);
            return Future.failedFuture(failure);
        }
        return operation
                .compose(subscription -> {
                    synchronized (this) {
                        if (states.get(state.id) == state
                                && clock.instant().isBefore(state.expiresAt)) {
                            state.subscription = subscription;
                            return Future.succeededFuture(state.summary());
                        }
                    }
                    return unsubscribe(subscription).compose(ignored -> Future.failedFuture(
                            new PubSubManagementException(
                                    PubSubManagementException.Code.EXPIRED)));
                })
                .onFailure(ignored -> removeState(state));
    }

    public synchronized Snapshot snapshot(String subscriptionId, String actor, String setupId) {
        State state = owned(subscriptionId, actor, setupId);
        return new Snapshot(
                state.summary(),
                List.copyOf(state.messages),
                state.messages.isEmpty() ? state.nextEventId : state.messages.getFirst().eventId());
    }

    public synchronized StreamOpen openStream(
            String subscriptionId,
            String actor,
            String setupId,
            long afterEventId,
            Consumer<RetainedMessage> messageConsumer,
            Runnable serverClose) {
        if (afterEventId < 0) {
            throw new IllegalArgumentException("afterEventId must be non-negative");
        }
        Objects.requireNonNull(messageConsumer, "messageConsumer");
        Objects.requireNonNull(serverClose, "serverClose");
        State state = owned(subscriptionId, actor, setupId);
        String clientId = UUID.randomUUID().toString();
        state.clients.put(clientId, new StreamClient(messageConsumer, serverClose));
        state.disconnectedAt = null;
        long oldest = state.messages.isEmpty()
                ? state.nextEventId : state.messages.getFirst().eventId();
        boolean reset = afterEventId > 0 && oldest > afterEventId + 1;
        List<RetainedMessage> replay = state.messages.stream()
                .filter(message -> message.eventId > afterEventId)
                .toList();
        return new StreamOpen(
                state.summary(), replay, reset, oldest,
                new StreamAttachment(state, clientId));
    }

    public synchronized RetainedMessage retained(
            String subscriptionId,
            String actor,
            String setupId,
            String messageId) {
        State state = owned(subscriptionId, actor, setupId);
        return state.messages.stream()
                .filter(message -> message.messageId.equals(messageId))
                .findFirst()
                .orElseThrow(() -> new PubSubManagementException(
                        PubSubManagementException.Code.EXPIRED));
    }

    public Future<Void> delete(String subscriptionId, String actor, String setupId) {
        State state;
        synchronized (this) {
            state = owned(subscriptionId, actor, setupId);
            removeState(state);
        }
        closeClients(state);
        return unsubscribe(state.subscription);
    }

    @Override
    public Future<Void> closeSetup(String setupId) {
        Objects.requireNonNull(setupId, "setupId");
        List<State> removed;
        synchronized (this) {
            removed = states.values().stream()
                    .filter(state -> state.setupId.equals(setupId))
                    .toList();
            removed.forEach(this::removeState);
        }
        removed.forEach(this::closeClients);
        return unsubscribeAll(removed);
    }

    @Override
    public Future<Void> close() {
        List<State> removed;
        synchronized (this) {
            removed = List.copyOf(states.values());
            removed.forEach(this::removeState);
        }
        removed.forEach(this::closeClients);
        return unsubscribeAll(removed);
    }

    public synchronized long size() {
        purgeExpired();
        return states.size();
    }

    private void retain(State state, PubSubMessage message) {
        RetainedMessage retained;
        List<StreamClient> clients;
        synchronized (this) {
            if (states.get(state.id) != state || !clock.instant().isBefore(state.expiresAt)) {
                return;
            }
            int bytes = message.payload().getBytes(StandardCharsets.UTF_8).length;
            retained = new RetainedMessage(
                    state.nextEventId++, nextArrivalSequence++, UUID.randomUUID().toString(),
                    message.channel(), message.payload(), message.contentType(),
                    bytes, Instant.ofEpochMilli(message.receivedAtEpochMillis()));
            state.messages.addLast(retained);
            state.retainedBytes += bytes;
            processBytes += bytes;
            while (state.messages.size() > state.bufferLimit
                    || state.retainedBytes > maximumBytesPerSubscription) {
                evictOldest(state);
            }
            while (processBytes > maximumProcessBytes) {
                State oldest = states.values().stream()
                        .filter(candidate -> !candidate.messages.isEmpty())
                        .min(Comparator.comparingLong(candidate ->
                                candidate.messages.getFirst().arrivalSequence()))
                        .orElse(null);
                if (oldest == null) break;
                evictOldest(oldest);
            }
            refreshTelemetry();
            clients = List.copyOf(state.clients.values());
        }
        for (StreamClient client : clients) {
            try {
                client.messageConsumer.accept(retained);
            } catch (RuntimeException failure) {
                LOGGER.warn("management.pubsub.stream.delivery_failed");
            }
        }
    }

    private State owned(String id, String actor, String setupId) {
        purgeExpired();
        State state = states.get(id);
        if (state == null || !state.actor.equals(actor) || !state.setupId.equals(setupId)) {
            throw new PubSubManagementException(PubSubManagementException.Code.NOT_FOUND);
        }
        return state;
    }

    private void purgeExpired() {
        Instant now = clock.instant();
        List<State> expired = states.values().stream()
                .filter(state -> !now.isBefore(state.expiresAt)
                        || (state.clients.isEmpty() && state.disconnectedAt != null
                        && !now.isBefore(state.disconnectedAt.plus(DISCONNECTED_LIFETIME))))
                .toList();
        expired.forEach(state -> {
            removeState(state);
            closeClients(state);
            if (state.subscription != null) {
                unsubscribe(state.subscription).onFailure(ignored ->
                        LOGGER.warn("management.pubsub.subscription.expiry_cleanup_failed"));
            }
        });
    }

    private static Future<Void> unsubscribeAll(List<State> states) {
        List<Future<Void>> operations = states.stream()
                .map(state -> unsubscribe(state.subscription))
                .toList();
        return operations.isEmpty() ? Future.succeededFuture() : Future.all(operations).mapEmpty();
    }

    private static Future<Void> unsubscribe(Subscription subscription) {
        if (subscription == null) return Future.succeededFuture();
        try {
            return subscription.unsubscribe();
        } catch (RuntimeException failure) {
            return Future.failedFuture(failure);
        }
    }

    private void removeState(State state) {
        if (states.remove(state.id, state)) {
            processBytes -= state.retainedBytes;
            state.retainedBytes = 0;
            state.messages.clear();
            refreshTelemetry();
        }
    }

    private void closeClients(State state) {
        List<StreamClient> clients;
        synchronized (this) {
            clients = List.copyOf(state.clients.values());
            state.clients.clear();
        }
        for (StreamClient client : clients) {
            try {
                client.serverClose.run();
            } catch (RuntimeException failure) {
                LOGGER.warn("management.pubsub.stream.close_failed");
            }
        }
    }

    private void evictOldest(State state) {
        RetainedMessage removed = state.messages.pollFirst();
        if (removed != null) {
            state.retainedBytes -= removed.payloadBytes;
            processBytes -= removed.payloadBytes;
            if (runtimeMonitor != null) runtimeMonitor.bufferEvicted();
        }
    }

    private void refreshTelemetry() {
        if (runtimeMonitor == null) return;
        runtimeMonitor.pubSubSubscriptions(states.size());
        runtimeMonitor.retainedPayloadBytes(processBytes);
    }

    public record Summary(
            String subscriptionId,
            String channel,
            int bufferLimit,
            Instant createdAt,
            Instant expiresAt) {
    }

    public record Snapshot(
            Summary summary,
            List<RetainedMessage> messages,
            long oldestAvailableEventId) {
        public Snapshot {
            messages = List.copyOf(messages);
        }
    }

    public record RetainedMessage(
            long eventId,
            long arrivalSequence,
            String messageId,
            String channel,
            String payload,
            String contentType,
            int payloadBytes,
            Instant receivedAt) {
    }

    public record StreamOpen(
            Summary summary,
            List<RetainedMessage> replay,
            boolean reset,
            long oldestAvailableEventId,
            StreamAttachment attachment) {
        public StreamOpen {
            replay = List.copyOf(replay);
            Objects.requireNonNull(attachment, "attachment");
        }
    }

    public final class StreamAttachment implements AutoCloseable {
        private final State state;
        private final String clientId;
        private final AtomicBoolean closed = new AtomicBoolean();

        private StreamAttachment(State state, String clientId) {
            this.state = state;
            this.clientId = clientId;
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) return;
            synchronized (ManagementPubSubSubscriptions.this) {
                if (state.clients.remove(clientId) != null && state.clients.isEmpty()) {
                    state.disconnectedAt = clock.instant();
                }
            }
        }
    }

    private static final class State {
        private final String id;
        private final String actor;
        private final String setupId;
        private final String channel;
        private final int bufferLimit;
        private final Instant createdAt;
        private final Instant expiresAt;
        private final ArrayDeque<RetainedMessage> messages = new ArrayDeque<>();
        private final Map<String, StreamClient> clients = new HashMap<>();
        private long nextEventId = 1;
        private long retainedBytes;
        private Subscription subscription;
        private Instant disconnectedAt;

        private State(
                String id, String actor, String setupId, String channel,
                int bufferLimit, Instant createdAt, Instant expiresAt) {
            this.id = id;
            this.actor = actor;
            this.setupId = setupId;
            this.channel = channel;
            this.bufferLimit = bufferLimit;
            this.createdAt = createdAt;
            this.expiresAt = expiresAt;
            this.disconnectedAt = createdAt;
        }

        private Summary summary() {
            return new Summary(id, channel, bufferLimit, createdAt, expiresAt);
        }
    }

    private record StreamClient(
            Consumer<RetainedMessage> messageConsumer,
            Runnable serverClose) {
    }
}
