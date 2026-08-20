package dev.mars.peegeeq.cache.observability.metrics;

import dev.mars.peegeeq.cache.core.telemetry.CacheOperation;
import dev.mars.peegeeq.cache.core.telemetry.CacheTelemetry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class MicrometerCacheTelemetryTest {

    @Test
    void exportsOperationalMetricsWithBoundedTags() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MicrometerCacheTelemetry telemetry = new MicrometerCacheTelemetry(registry);

        CacheTelemetry.OperationSpan success = telemetry.startOperation(CacheOperation.CACHE_GET);
        success.complete(null);
        CacheTelemetry.OperationSpan failure = telemetry.startOperation(CacheOperation.CACHE_SET);
        failure.complete(new IllegalStateException("database unavailable"));
        telemetry.recordLockContention();
        telemetry.recordExpirySweep(7, Duration.ofMillis(12), Duration.ofSeconds(3), null);
        telemetry.recordPubSubReconnect(2, Duration.ofMillis(5), null);
        telemetry.recordNotificationDispatch(3, Duration.ofMillis(2));
        telemetry.recordActiveSubscriptions(4);
        telemetry.recordWriteBehindOverflow();
        telemetry.recordWriteBehindFlush(5, Duration.ofMillis(4), null);
        telemetry.recordWriteBehindFlush(2, Duration.ofMillis(6), new IllegalStateException("flush failed"));
        telemetry.recordWriteBehindDiscard(2);

        assertEquals(1, registry.get("peegeeq.cache.operation")
                .tag("operation", "cache.get").tag("outcome", "success").timer().count());
        assertEquals(1, registry.get("peegeeq.cache.operation")
                .tag("operation", "cache.set").tag("outcome", "failure").timer().count());
        assertEquals(1, registry.get("peegeeq.cache.lock.contention").counter().count());
        assertEquals(7, registry.get("peegeeq.cache.expiry.rows").summary().totalAmount());
        assertEquals(3, registry.get("peegeeq.cache.expiry.lag").summary().totalAmount());
        assertEquals(1, registry.get("peegeeq.cache.pubsub.reconnect")
                .tag("outcome", "success").timer().count());
        assertEquals(4, registry.get("peegeeq.cache.pubsub.subscriptions").gauge().value());
        assertNotNull(registry.find("peegeeq.cache.pubsub.notification.dispatch").timer());
        assertEquals(1, registry.get("peegeeq.cache.write.behind.overflow").counter().count());
        assertEquals(2, registry.get("peegeeq.cache.write.behind.discard").counter().count());
        assertEquals(7, registry.get("peegeeq.cache.write.behind.flush.entries").summary().totalAmount());
        assertEquals(1, registry.get("peegeeq.cache.write.behind.flush")
                .tag("outcome", "success").timer().count());
        assertEquals(1, registry.get("peegeeq.cache.write.behind.flush")
                .tag("outcome", "failure").timer().count());
        assertEquals(0, registry.get("peegeeq.cache.operations.active").gauge().value());
    }

    @Test
    void aggregatesGaugesAcrossAdaptersSharingARegistry() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MicrometerCacheTelemetry first = new MicrometerCacheTelemetry(registry);
        MicrometerCacheTelemetry second = new MicrometerCacheTelemetry(registry);

        CacheTelemetry.OperationSpan firstOperation = first.startOperation(CacheOperation.CACHE_GET);
        CacheTelemetry.OperationSpan secondOperation = second.startOperation(CacheOperation.CACHE_SET);
        first.recordActiveSubscriptions(2);
        second.recordActiveSubscriptions(3);
        first.recordLifecycle(true);
        second.recordLifecycle(true);

        assertEquals(2, registry.get("peegeeq.cache.operations.active").gauge().value());
        assertEquals(5, registry.get("peegeeq.cache.pubsub.subscriptions").gauge().value());
        assertEquals(2, registry.get("peegeeq.cache.runtime.started").gauge().value());

        firstOperation.complete(null);
        secondOperation.complete(null);
        first.recordActiveSubscriptions(1);
        first.recordLifecycle(false);

        assertEquals(0, registry.get("peegeeq.cache.operations.active").gauge().value());
        assertEquals(4, registry.get("peegeeq.cache.pubsub.subscriptions").gauge().value());
        assertEquals(1, registry.get("peegeeq.cache.runtime.started").gauge().value());
    }
}
