package dev.mars.peegeeq.cache.api.pubsub;

import dev.mars.peegeeq.cache.api.model.PublishRequest;
import dev.mars.peegeeq.cache.api.model.PubSubMessage;
import io.vertx.core.Future;

import java.util.function.Consumer;

public interface PubSubService {

    Future<Integer> publish(PublishRequest request);

    Future<Subscription> subscribe(String channel, Consumer<PubSubMessage> handler);
}
