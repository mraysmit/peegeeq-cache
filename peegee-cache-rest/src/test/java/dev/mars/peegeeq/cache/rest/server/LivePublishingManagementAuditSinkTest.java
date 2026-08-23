package dev.mars.peegeeq.cache.rest.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mars.peegeeq.cache.api.management.ManagementActivityQuery;
import dev.mars.peegeeq.cache.api.management.ManagementAuditAction;
import dev.mars.peegeeq.cache.api.management.ManagementAuditIntent;
import dev.mars.peegeeq.cache.api.management.ManagementAuditOutcome;
import dev.mars.peegeeq.cache.api.management.ManagementAuditReservation;
import dev.mars.peegeeq.cache.api.management.ManagementAuditSink;
import dev.mars.peegeeq.cache.api.management.ManagementAuditTerminalOutcome;
import dev.mars.peegeeq.cache.api.management.ManagementResourceType;
import io.vertx.core.Future;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class LivePublishingManagementAuditSinkTest {

    @Test
    void publishesProcessLocalActivityAndResourceChangeOnlyAfterDurableCompletion() throws Exception {
        RecordingSink durable = new RecordingSink();
        ManagementActivityStore activity = new ManagementActivityStore(10);
        ManagementLiveEventHub hub = new ManagementLiveEventHub(
                Clock.fixed(Instant.parse("2026-08-23T12:00:00Z"), ZoneOffset.UTC),
                Duration.ofMinutes(5), 10);
        List<ManagementLiveEventHub.Event> observed = new ArrayList<>();
        hub.open("orders", null, observed::add, () -> { });
        LivePublishingManagementAuditSink sink = new LivePublishingManagementAuditSink(
                durable, activity, hub);
        ManagementAuditIntent intent = new ManagementAuditIntent(
                "audit-event-1", Instant.parse("2026-08-23T11:59:59Z"), "operator",
                Set.of("operator"), ManagementAuditAction.SET_COUNTER, "orders",
                ManagementResourceType.COUNTER, Map.of(), 4L, null,
                "127.0.0.1", "correlation-1");

        ManagementAuditReservation reservation = await(sink.reserveIntent(intent));
        assertEquals(0, observed.size());
        await(sink.complete(reservation, new ManagementAuditOutcome(
                ManagementAuditTerminalOutcome.SUCCEEDED, "COUNTER_SET", 5L)));

        assertEquals(List.of("activity.created", "resource.changed"),
                observed.stream().map(ManagementLiveEventHub.Event::type).toList());
        var resource = new ObjectMapper().readTree(observed.get(1).data());
        assertEquals("COUNTER", resource.path("resourceType").asText());
        assertEquals("5", resource.path("resultingVersion").asText());
        assertEquals("operator", resource.path("actor").asText());
        assertFalse(resource.has("value"));
        assertEquals("audit-event-1", activity.recent("orders", new ManagementActivityQuery(
                null, 10, null, null, null)).items().getFirst().eventId());

        await(sink.complete(reservation, new ManagementAuditOutcome(
                ManagementAuditTerminalOutcome.SUCCEEDED, "COUNTER_SET", 5L)));
        assertEquals(2, observed.size(), "idempotent audit completion must not duplicate events");
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get();
    }

    private static final class RecordingSink implements ManagementAuditSink {
        @Override
        public Future<ManagementAuditReservation> reserveIntent(ManagementAuditIntent intent) {
            return Future.succeededFuture(new ManagementAuditReservation(
                    "reservation-1", intent.eventId(), "test"));
        }

        @Override
        public Future<Void> complete(
                ManagementAuditReservation reservation, ManagementAuditOutcome outcome) {
            return Future.succeededFuture();
        }
    }
}
