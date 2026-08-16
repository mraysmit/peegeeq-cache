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
        assertEquals(0, registry.get("peegeeq.cache.operations.active").gauge().value());
    }
}
