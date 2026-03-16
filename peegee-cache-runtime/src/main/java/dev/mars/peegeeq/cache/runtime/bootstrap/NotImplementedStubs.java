package dev.mars.peegeeq.cache.runtime.bootstrap;

import dev.mars.peegeeq.cache.api.model.PublishRequest;
import dev.mars.peegeeq.cache.api.model.PubSubMessage;
import dev.mars.peegeeq.cache.api.model.ScanRequest;
import dev.mars.peegeeq.cache.api.model.ScanResult;
import dev.mars.peegeeq.cache.api.pubsub.PubSubService;
import dev.mars.peegeeq.cache.api.pubsub.Subscription;
import dev.mars.peegeeq.cache.api.scan.ScanService;
import io.vertx.core.Future;

import java.util.function.Consumer;

/**
 * Temporary no-op service implementations for APIs scheduled for a later phase.
 */
final class NotImplementedStubs {

    private NotImplementedStubs() {}

    static ScanService scanService() {
        return request -> Future.failedFuture(notImplemented("scan", request));
    }

    static PubSubService pubSubService() {
        return new PubSubService() {
            @Override
            public Future<Integer> publish(PublishRequest request) {
                return Future.failedFuture(notImplemented("publish", request));
            }

            @Override
            public Future<Subscription> subscribe(String channel, Consumer<PubSubMessage> handler) {
                return Future.failedFuture(notImplemented("subscribe", channel));
            }
        };
    }

    private static UnsupportedOperationException notImplemented(String operation, Object detail) {
        return new UnsupportedOperationException(
                "Operation '" + operation + "' is not implemented yet for PostgreSQL runtime bootstrap"
                        + (detail != null ? " (detail=" + detail + ")" : "")
        );
    }
}
