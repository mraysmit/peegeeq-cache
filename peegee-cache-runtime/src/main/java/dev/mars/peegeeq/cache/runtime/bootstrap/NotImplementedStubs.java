package dev.mars.peegeeq.cache.runtime.bootstrap;

import dev.mars.peegeeq.cache.api.model.PublishRequest;
import dev.mars.peegeeq.cache.api.model.PubSubMessage;
import dev.mars.peegeeq.cache.api.pubsub.PubSubService;
import dev.mars.peegeeq.cache.api.pubsub.Subscription;
import dev.mars.peegeeq.cache.core.metrics.CacheMetrics;
import dev.mars.peegeeq.cache.core.telemetry.CacheOperation;
import io.vertx.core.Future;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Fail-fast service implementations for capabilities unavailable in the current configuration.
 */
final class NotImplementedStubs {

    private NotImplementedStubs() {}

    static PubSubService pubSubService(CacheMetrics metrics) {
        Objects.requireNonNull(metrics, "metrics");
        return new PubSubService() {
            @Override
            public Future<Integer> publish(PublishRequest request) {
                return metrics.observe(CacheOperation.PUBSUB_PUBLISH,
                        () -> Future.failedFuture(pubSubUnavailable()));
            }

            @Override
            public Future<Subscription> subscribe(String channel, Consumer<PubSubMessage> handler) {
                return metrics.observe(CacheOperation.PUBSUB_SUBSCRIBE,
                        () -> Future.failedFuture(pubSubUnavailable()));
            }
        };
    }

    private static UnsupportedOperationException pubSubUnavailable() {
        return new UnsupportedOperationException(
                "PostgreSQL pub/sub is unavailable because connectOptions were not configured");
    }
}
