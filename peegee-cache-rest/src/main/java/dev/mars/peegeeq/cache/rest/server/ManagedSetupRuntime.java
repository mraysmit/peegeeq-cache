package dev.mars.peegeeq.cache.rest.server;

import dev.mars.peegeeq.cache.api.management.ManagementService;
import dev.mars.peegeeq.cache.api.management.UnsupportedManagementService;
import dev.mars.peegeeq.cache.api.PeeGeeCache;
import dev.mars.peegeeq.cache.api.pubsub.PubSubService;
import io.vertx.core.Future;

import java.time.Instant;

public interface ManagedSetupRuntime {
    Future<Void> verifyReady();

    default Future<SetupHealth> health() {
        long started = System.nanoTime();
        return verifyReady()
                .map(ignored -> new SetupHealth(
                        SetupHealthSummary.Status.UP,
                        true,
                        elapsedMillis(started),
                        Instant.now(),
                        "Database reachable and schema ready"))
                .recover(ignored -> Future.succeededFuture(new SetupHealth(
                        SetupHealthSummary.Status.DOWN,
                        false,
                        elapsedMillis(started),
                        Instant.now(),
                        "Database or schema unavailable")));
    }

    default ManagementService management() {
        return UnsupportedManagementService.instance();
    }

    default PubSubService pubSub() {
        throw new SetupRegistryException(409, "SETUP_PUBSUB_UNAVAILABLE", "Setup pub/sub is unavailable");
    }

    /** Complete cache facade used by routes that expose the public backend service surface. */
    default PeeGeeCache cache() {
        throw new SetupRegistryException(409, "SETUP_CACHE_UNAVAILABLE", "Setup cache is unavailable");
    }

    default boolean supportsPubSub() {
        return false;
    }

    default boolean supportsPubSubPayloadReveal() {
        return false;
    }

    default boolean supportsBatchEntryOperations() {
        return false;
    }

    default boolean supportsValueScan() {
        return false;
    }

    default boolean supportsCacheMetrics() {
        return false;
    }

    default boolean supportsOwnerLockOperations() {
        return false;
    }

    Future<Void> closeAsync();

    private static long elapsedMillis(long started) {
        return Math.max(0, (System.nanoTime() - started) / 1_000_000);
    }
}
