package dev.mars.peegeeq.cache.rest.server;

import dev.mars.peegeeq.cache.api.management.ManagementService;
import dev.mars.peegeeq.cache.api.management.UnsupportedManagementService;
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

    Future<Void> closeAsync();

    private static long elapsedMillis(long started) {
        return Math.max(0, (System.nanoTime() - started) / 1_000_000);
    }
}
