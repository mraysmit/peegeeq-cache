package dev.mars.peegeeq.cache.examples;

import dev.mars.peegeeq.cache.api.model.LockAcquireRequest;
import dev.mars.peegeeq.cache.api.model.LockKey;
import dev.mars.peegeeq.cache.api.model.LockReleaseRequest;
import dev.mars.peegeeq.cache.api.model.LockRenewRequest;
import dev.mars.peegeeq.cache.api.model.PublishRequest;
import io.vertx.core.Promise;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;

/** Runnable distributed-lock and PostgreSQL LISTEN/NOTIFY example. */
public final class CoordinationAndPubSubExample {

    private static final Logger log = LoggerFactory.getLogger(CoordinationAndPubSubExample.class);

    private CoordinationAndPubSubExample() {
    }

    public static void main(String[] args) throws Exception {
        ExampleRuntimeSupport.runWithConfiguredManager(log, "coordination and pub/sub example", manager -> {
            LockKey key = new LockKey("example", "daily-reconciliation");
            String owner = "worker-1";
            var acquired = ExampleRuntimeSupport.await(manager.cache().locks().acquire(
                    new LockAcquireRequest(key, owner, Duration.ofSeconds(30), false, true)));
            log.info("lock acquired={} fencingToken={}", acquired.acquired(), acquired.fencingToken());
            if (acquired.acquired()) {
                boolean renewed = ExampleRuntimeSupport.await(manager.cache().locks().renew(
                        new LockRenewRequest(key, owner, Duration.ofSeconds(30))));
                log.info("lock renewed={}", renewed);
                boolean released = ExampleRuntimeSupport.await(manager.cache().locks().release(
                        new LockReleaseRequest(key, owner)));
                log.info("lock released={}", released);
            }

            Promise<String> received = Promise.promise();
            var subscription = ExampleRuntimeSupport.await(manager.cache().pubSub().subscribe(
                    "inventory-events", message -> received.tryComplete(message.payload())));
            ExampleRuntimeSupport.await(manager.cache().pubSub().publish(
                    new PublishRequest("inventory-events", "inventory-refreshed", "text/plain")));
            log.info("notification received payload={}", ExampleRuntimeSupport.await(received.future()));
            ExampleRuntimeSupport.await(subscription.unsubscribe());
        });
    }
}
