package dev.mars.peegeeq.cache.rest.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManagementLiveEventHubTest {

    @Test
    void boundedHistoryResumesKnownIdsAndResetsUnavailableHistory() {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-23T12:00:00Z"));
        ManagementLiveEventHub hub = new ManagementLiveEventHub(clock, Duration.ofMinutes(5), 2);
        ManagementLiveEventHub.Event first = hub.publish("orders", "activity.created", "{\"n\":1}");
        ManagementLiveEventHub.Event second = hub.publish("orders", "activity.created", "{\"n\":2}");

        ManagementLiveEventHub.Open resumed = hub.open(
                "orders", first.eventId(), ignored -> { }, () -> { });
        assertFalse(resumed.reset());
        assertEquals(List.of(second), resumed.replay());
        resumed.attachment().close();

        ManagementLiveEventHub.Event third = hub.publish(
                "orders", "resource.changed", "{\"n\":3}");
        ManagementLiveEventHub.Open reset = hub.open(
                "orders", first.eventId(), ignored -> { }, () -> { });
        assertTrue(reset.reset());
        assertEquals(second.eventId(), reset.oldestAvailableEventId());
        assertEquals(List.of(second, third), reset.replay());
        reset.attachment().close();
    }

    @Test
    void liveDeliveryAndLifecycleCloseAreExactlyOnceAndHistoryAgesOut() throws Exception {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-23T12:00:00Z"));
        ManagementLiveEventHub hub = new ManagementLiveEventHub(clock, Duration.ofMinutes(5), 10);
        List<String> types = new ArrayList<>();
        AtomicInteger closes = new AtomicInteger();
        ManagementLiveEventHub.Open open = hub.open(
                "orders", null, event -> types.add(event.type()), closes::incrementAndGet);

        hub.publish("orders", "health.changed", "{}");
        hub.publish("other", "health.changed", "{}");
        hub.closeSetup("orders").toCompletionStage().toCompletableFuture().join();

        assertEquals(List.of("health.changed", "setup.state.changed"), types);
        assertEquals(1, closes.get());
        open.attachment().close();
        assertEquals(1, closes.get());
        clock.advance(Duration.ofMinutes(5));
        assertEquals(0, hub.retainedEventCount());
    }

    @Test
    void healthEventsMatchTheOpenApiNumericLatencyContract() throws Exception {
        ManagementLiveEventHub hub = new ManagementLiveEventHub(
                Clock.fixed(Instant.parse("2026-08-29T10:00:00Z"), ZoneOffset.UTC),
                Duration.ofMinutes(5), 10);
        List<ManagementLiveEventHub.Event> events = new ArrayList<>();
        ManagementLiveEventHub.Open open = hub.open("orders", null, events::add, () -> { });

        hub.healthChanged("orders", new SetupHealth(
                SetupHealthSummary.Status.UP, true, 7,
                Instant.parse("2026-08-29T10:00:00Z"), "Ready"));

        JsonNode data = new ObjectMapper().readTree(events.getFirst().data());
        assertTrue(data.get("latencyMillis").isIntegralNumber());
        assertEquals(7, data.get("latencyMillis").longValue());
        open.attachment().close();
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
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
