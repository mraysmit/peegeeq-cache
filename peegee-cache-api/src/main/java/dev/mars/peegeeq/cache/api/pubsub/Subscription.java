package dev.mars.peegeeq.cache.api.pubsub;

import io.vertx.core.Future;

public interface Subscription {
    String channel();
    Future<Void> unsubscribe();
}
