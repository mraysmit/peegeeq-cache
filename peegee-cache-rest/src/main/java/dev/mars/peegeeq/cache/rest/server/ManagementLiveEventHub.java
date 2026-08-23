package dev.mars.peegeeq.cache.rest.server;

import io.vertx.core.Future;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** Bounded five-minute process-local event history shared by live management transports. */
public final class ManagementLiveEventHub implements SetupScopeLifecycle {

    private static final Logger LOGGER = LoggerFactory.getLogger(ManagementLiveEventHub.class);

    private final Clock clock;
    private final Duration retention;
    private final int capacity;
    private final AtomicLong nextEventId = new AtomicLong(1);
    private final ArrayDeque<Event> events = new ArrayDeque<>();
    private final Map<String, Client> clients = new HashMap<>();
    private final ObjectMapper json = new ObjectMapper();

    public ManagementLiveEventHub(Clock clock, Duration retention, int capacity) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.retention = Objects.requireNonNull(retention, "retention");
        if (retention.isZero() || retention.isNegative() || capacity < 1) {
            throw new IllegalArgumentException("event retention and capacity must be positive");
        }
        this.capacity = capacity;
    }

    public Event publish(String setupId, String type, String data) {
        Event event;
        List<Client> recipients;
        synchronized (this) {
            purgeExpired();
            event = new Event(
                    Long.toString(nextEventId.getAndIncrement()), type, clock.instant(), setupId, data);
            events.addLast(event);
            while (events.size() > capacity) events.removeFirst();
            recipients = clients.values().stream()
                    .filter(client -> client.setupId.equals(setupId))
                    .toList();
        }
        recipients.forEach(client -> deliver(client, event));
        return event;
    }

    public synchronized Open open(
            String setupId,
            String afterEventId,
            Consumer<Event> eventConsumer,
            Runnable serverClose) {
        Objects.requireNonNull(setupId, "setupId");
        Objects.requireNonNull(eventConsumer, "eventConsumer");
        Objects.requireNonNull(serverClose, "serverClose");
        purgeExpired();
        List<Event> setupEvents = events.stream()
                .filter(event -> event.setupId.equals(setupId))
                .toList();
        boolean reset = false;
        List<Event> replay = List.of();
        if (afterEventId != null) {
            int index = -1;
            for (int i = 0; i < setupEvents.size(); i++) {
                if (setupEvents.get(i).eventId.equals(afterEventId)) {
                    index = i;
                    break;
                }
            }
            if (index < 0) {
                reset = true;
                replay = setupEvents;
            } else {
                replay = setupEvents.subList(index + 1, setupEvents.size());
            }
        }
        String clientId = UUID.randomUUID().toString();
        clients.put(clientId, new Client(setupId, eventConsumer, serverClose));
        String oldest = setupEvents.isEmpty() ? null : setupEvents.getFirst().eventId;
        return new Open(replay, reset, oldest, new Attachment(clientId));
    }

    @Override
    public Future<Void> healthChanged(String setupId, SetupHealth health) {
        ObjectNode data = json.createObjectNode();
        data.put("status", health.status().name());
        data.put("schemaReady", health.schemaReady());
        data.put("latencyMillis", Long.toString(health.latencyMillis()));
        data.put("checkedAt", health.checkedAt().toString());
        data.put("detail", health.detail());
        publish(setupId, "health.changed", data.toString());
        return Future.succeededFuture();
    }

    @Override
    public Future<Void> closeSetup(String setupId) {
        publish(setupId, "setup.state.changed", "{\"state\":\"DETACHED\"}");
        closeClients(Set.of(setupId));
        return Future.succeededFuture();
    }

    @Override
    public Future<Void> close() {
        Set<String> setupIds;
        synchronized (this) {
            setupIds = clients.values().stream()
                    .map(client -> client.setupId)
                    .collect(java.util.stream.Collectors.toSet());
        }
        setupIds.forEach(setupId -> publish(
                setupId, "server.shutting_down", "{\"reason\":\"SERVER_SHUTDOWN\"}"));
        closeClients(setupIds);
        return Future.succeededFuture();
    }

    public synchronized int retainedEventCount() {
        purgeExpired();
        return events.size();
    }

    private void closeClients(Set<String> setupIds) {
        List<Client> removed = new ArrayList<>();
        synchronized (this) {
            clients.entrySet().removeIf(entry -> {
                if (!setupIds.contains(entry.getValue().setupId)) return false;
                removed.add(entry.getValue());
                return true;
            });
        }
        removed.forEach(this::closeClient);
    }

    private void purgeExpired() {
        Instant cutoff = clock.instant().minus(retention);
        while (!events.isEmpty() && !events.getFirst().occurredAt.isAfter(cutoff)) {
            events.removeFirst();
        }
    }

    private void deliver(Client client, Event event) {
        try {
            client.eventConsumer.accept(event);
        } catch (RuntimeException failure) {
            LOGGER.warn("management.live_event.delivery_failed");
        }
    }

    private void closeClient(Client client) {
        try {
            client.serverClose.run();
        } catch (RuntimeException failure) {
            LOGGER.warn("management.live_event.client_close_failed");
        }
    }

    public record Event(
            String eventId,
            String type,
            Instant occurredAt,
            String setupId,
            String data) {
        public Event {
            Objects.requireNonNull(eventId, "eventId");
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(occurredAt, "occurredAt");
            Objects.requireNonNull(setupId, "setupId");
            Objects.requireNonNull(data, "data");
            if (eventId.length() > 128 || type.length() > 64 || setupId.length() > 64
                    || data.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 1024 * 1024) {
                throw new IllegalArgumentException("live event exceeds its bounded contract");
            }
        }
    }

    public record Open(
            List<Event> replay,
            boolean reset,
            String oldestAvailableEventId,
            Attachment attachment) {
        public Open {
            replay = List.copyOf(replay);
            Objects.requireNonNull(attachment, "attachment");
        }
    }

    public final class Attachment implements AutoCloseable {
        private final String clientId;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Attachment(String clientId) {
            this.clientId = clientId;
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) return;
            synchronized (ManagementLiveEventHub.this) {
                clients.remove(clientId);
            }
        }
    }

    private record Client(
            String setupId,
            Consumer<Event> eventConsumer,
            Runnable serverClose) {
    }
}
