package dev.mars.peegeeq.cache.rest.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.mars.peegeeq.cache.api.management.ManagementActivityEvent;
import dev.mars.peegeeq.cache.api.management.ManagementActivityResource;
import dev.mars.peegeeq.cache.api.management.ManagementActivityResourceType;
import dev.mars.peegeeq.cache.api.management.ManagementAuditException;
import dev.mars.peegeeq.cache.api.management.ManagementAuditIntent;
import dev.mars.peegeeq.cache.api.management.ManagementAuditOutcome;
import dev.mars.peegeeq.cache.api.management.ManagementAuditReservation;
import dev.mars.peegeeq.cache.api.management.ManagementAuditSink;
import dev.mars.peegeeq.cache.api.management.ManagementResourceType;
import io.vertx.core.Future;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** Publishes bounded process-local live events after authoritative audit completion succeeds. */
public final class LivePublishingManagementAuditSink implements
        ManagementAuditSink, ManagementActivityPublishingAuditSink {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(LivePublishingManagementAuditSink.class);
    private static final int MAX_PENDING_INTENTS = 10_000;

    private final ManagementAuditSink durable;
    private final ManagementActivityStore activity;
    private final ManagementLiveEventHub events;
    private final ObjectMapper json = new ObjectMapper();
    private final Map<String, ManagementAuditIntent> pending = new HashMap<>();

    public LivePublishingManagementAuditSink(
            ManagementAuditSink durable,
            ManagementActivityStore activity,
            ManagementLiveEventHub events) {
        this.durable = Objects.requireNonNull(durable, "durable");
        this.activity = Objects.requireNonNull(activity, "activity");
        this.events = Objects.requireNonNull(events, "events");
    }

    @Override
    public Future<ManagementAuditReservation> reserveIntent(ManagementAuditIntent intent) {
        Objects.requireNonNull(intent, "intent");
        return durable.reserveIntent(intent).compose(reservation -> {
            synchronized (pending) {
                if (pending.size() >= MAX_PENDING_INTENTS) {
                    return Future.failedFuture(new ManagementAuditException(
                            "Live event pending-intent capacity is exhausted"));
                }
                ManagementAuditIntent previous = pending.putIfAbsent(
                        reservation.reservationId(), intent);
                if (previous != null) {
                    return Future.failedFuture(new ManagementAuditException(
                            "Audit reservation identifier is already pending"));
                }
            }
            return Future.succeededFuture(reservation);
        });
    }

    @Override
    public Future<Void> complete(
            ManagementAuditReservation reservation, ManagementAuditOutcome outcome) {
        Objects.requireNonNull(reservation, "reservation");
        Objects.requireNonNull(outcome, "outcome");
        return durable.complete(reservation, outcome).onSuccess(ignored -> {
            ManagementAuditIntent intent;
            synchronized (pending) {
                intent = pending.remove(reservation.reservationId());
            }
            if (intent == null) return;
            try {
                publish(intent, outcome);
            } catch (RuntimeException failure) {
                LOGGER.warn("management.live_event.audit_projection_failed");
            }
        });
    }

    @Override
    public boolean isMutationReady() {
        synchronized (pending) {
            return pending.size() < MAX_PENDING_INTENTS && durable.isMutationReady();
        }
    }

    private void publish(ManagementAuditIntent intent, ManagementAuditOutcome outcome) {
        ManagementActivityResourceType resourceType = activityResourceType(intent.resourceType());
        ManagementActivityEvent activityEvent = new ManagementActivityEvent(
                intent.eventId(), intent.occurredAt(), intent.actor(), intent.action().name(),
                outcome.outcome(), intent.setupId(), null,
                new ManagementActivityResource(resourceType, null),
                outcome.code(), intent.correlationId());
        activity.publish(activityEvent);
        events.publish(intent.setupId(), "activity.created", activityJson(activityEvent).toString());
        if (intent.resourceType() != ManagementResourceType.SETUP) {
            events.publish(
                    intent.setupId(), "resource.changed",
                    resourceJson(intent, outcome, resourceType).toString());
        }
    }

    private ObjectNode activityJson(ManagementActivityEvent event) {
        ObjectNode node = json.createObjectNode();
        node.put("eventId", event.eventId());
        node.put("occurredAt", event.occurredAt().toString());
        node.put("actor", event.actor());
        node.put("action", event.action());
        node.put("outcome", event.outcome().name());
        node.put("setupId", event.setupId());
        node.putNull("namespace");
        ObjectNode resource = node.putObject("resource");
        resource.put("type", event.resource().type().name());
        resource.putNull("identifier");
        node.put("summary", event.summary());
        node.put("correlationId", event.correlationId());
        return node;
    }

    private ObjectNode resourceJson(
            ManagementAuditIntent intent,
            ManagementAuditOutcome outcome,
            ManagementActivityResourceType resourceType) {
        ObjectNode node = json.createObjectNode();
        node.put("resourceType", resourceType.name());
        node.putNull("namespace");
        node.putNull("identifier");
        node.put("action", intent.action().name());
        if (outcome.resultingVersion() == null) node.putNull("resultingVersion");
        else node.put("resultingVersion", Long.toString(outcome.resultingVersion()));
        node.put("actor", intent.actor());
        node.put("outcome", outcome.outcome().name());
        node.put("correlationId", intent.correlationId());
        return node;
    }

    private static ManagementActivityResourceType activityResourceType(
            ManagementResourceType resourceType) {
        return switch (resourceType) {
            case SETUP -> ManagementActivityResourceType.SETUP;
            case COUNTER -> ManagementActivityResourceType.COUNTER;
            case LOCK -> ManagementActivityResourceType.LOCK;
            case PUBSUB_SUBSCRIPTION -> ManagementActivityResourceType.SUBSCRIPTION;
            case PUBSUB_CHANNEL, PUBSUB_MESSAGE -> ManagementActivityResourceType.PUBSUB_MESSAGE;
            case NAMESPACE, ENTRY, BULK_SELECTION -> ManagementActivityResourceType.CACHE_ENTRY;
        };
    }
}
