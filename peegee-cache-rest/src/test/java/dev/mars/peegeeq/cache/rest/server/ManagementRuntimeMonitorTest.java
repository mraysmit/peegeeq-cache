package dev.mars.peegeeq.cache.rest.server;

import dev.mars.peegeeq.cache.api.management.Availability;
import dev.mars.peegeeq.cache.api.management.ManagementAuditQueueState;
import dev.mars.peegeeq.cache.api.management.ManagementOperationStatus;
import dev.mars.peegeeq.cache.api.management.ManagementRuntimeLifecycleState;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class ManagementRuntimeMonitorTest {

    @Test
    void resourceLeasesTrackLiveGaugesExactlyOnce() {
        ManagementRuntimeMonitor monitor = new ManagementRuntimeMonitor();

        AutoCloseable subscription = monitor.trackPubSubSubscription();
        AutoCloseable pubSubSse = monitor.trackSseClient();
        AutoCloseable metricsSse = monitor.trackSseClient();
        AutoCloseable webSocket = monitor.trackWebSocketClient();
        monitor.retainedPayloadBytes(4096);
        monitor.bufferEvicted();
        monitor.bufferEvicted();
        monitor.streamReset();

        var active = monitor.snapshot(
                10, false, null, new ManagementAuditQueueState(0, 4096, true));
        assertEquals(1, active.pubSubSubscriptions());
        assertEquals(2, active.sseClients());
        assertEquals(1, active.webSocketClients());
        assertEquals(4096, active.retainedPayloadBytes());
        assertEquals(2, monitor.resourceSnapshot().bufferEvictions());
        assertEquals(1, monitor.resourceSnapshot().streamResets());

        assertDoesNotThrow(() -> {
            subscription.close();
            subscription.close();
            pubSubSse.close();
            pubSubSse.close();
            metricsSse.close();
            webSocket.close();
        });
        var closed = monitor.snapshot(
                10, false, null, new ManagementAuditQueueState(0, 4096, true));
        assertEquals(0, closed.pubSubSubscriptions());
        assertEquals(0, closed.sseClients());
        assertEquals(0, closed.webSocketClients());
    }

    @Test
    void snapshotsActiveAndTerminalOperationsWithoutHighCardinalityDimensions() {
        AtomicLong nanoTime = new AtomicLong(1_000_000_000L);
        ManagementRuntimeMonitor monitor = new ManagementRuntimeMonitor(
                Clock.fixed(Instant.parse("2026-08-23T09:30:00Z"), ZoneOffset.UTC),
                nanoTime::get);
        monitor.lifecycle(ManagementRuntimeLifecycleState.RUNNING);
        monitor.pubSubSubscriptions(2);
        monitor.streamResources(3, 4, 8192);

        ManagementRuntimeMonitor.Operation operation = monitor.begin("getRuntimeMonitoring");
        var active = monitor.snapshot(
                10, false, null, new ManagementAuditQueueState(1, 4096, true));

        assertEquals(1, active.activeOperations());
        assertEquals(ManagementOperationStatus.ACTIVE,
                active.operations().getFirst().status());
        assertEquals(Availability.UNAVAILABLE, active.pool().active().availability());
        assertEquals(10L, active.pool().maximum().value());
        assertEquals(2, active.pubSubSubscriptions());
        assertEquals(8192, active.retainedPayloadBytes());

        nanoTime.addAndGet(5_000_000L);
        operation.failed();
        operation.failed();
        var failed = monitor.snapshot(
                10, false, null, new ManagementAuditQueueState(0, 4096, true));

        assertEquals(0, failed.activeOperations());
        assertEquals(ManagementOperationStatus.FAILED,
                failed.operations().getFirst().status());
        assertEquals(1, failed.operations().getFirst().count());
        assertEquals(1, failed.operations().getFirst().errorCount());
        assertEquals(5, failed.operations().getFirst().latencyMillis());
    }
}
