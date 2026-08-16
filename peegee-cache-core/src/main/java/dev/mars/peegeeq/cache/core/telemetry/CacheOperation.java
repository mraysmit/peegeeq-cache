package dev.mars.peegeeq.cache.core.telemetry;

/**
 * Bounded operation names used by metrics and tracing adapters.
 * User-controlled namespaces and keys are deliberately excluded.
 */
public enum CacheOperation {
    CACHE_GET("cache.get"),
    CACHE_GET_MANY("cache.get_many"),
    CACHE_SET("cache.set"),
    CACHE_SET_MANY("cache.set_many"),
    CACHE_DELETE("cache.delete"),
    CACHE_DELETE_MANY("cache.delete_many"),
    CACHE_EXISTS("cache.exists"),
    CACHE_TTL("cache.ttl"),
    CACHE_EXPIRE("cache.expire"),
    CACHE_PERSIST("cache.persist"),
    CACHE_TOUCH("cache.touch"),
    COUNTER_INCREMENT("counter.increment"),
    COUNTER_DECREMENT("counter.decrement"),
    COUNTER_GET("counter.get"),
    COUNTER_SET("counter.set"),
    COUNTER_TTL("counter.ttl"),
    COUNTER_EXPIRE("counter.expire"),
    COUNTER_PERSIST("counter.persist"),
    COUNTER_DELETE("counter.delete"),
    LOCK_ACQUIRE("lock.acquire"),
    LOCK_RENEW("lock.renew"),
    LOCK_RELEASE("lock.release"),
    LOCK_IS_HELD("lock.is_held"),
    LOCK_CURRENT("lock.current"),
    SCAN("scan"),
    PUBSUB_PUBLISH("pubsub.publish"),
    PUBSUB_SUBSCRIBE("pubsub.subscribe"),
    PUBSUB_UNSUBSCRIBE("pubsub.unsubscribe"),
    ADMIN_ENTRY_STATS("admin.entry_stats"),
    SCHEMA_BOOTSTRAP("schema.bootstrap");

    private final String metricName;

    CacheOperation(String metricName) {
        this.metricName = metricName;
    }

    public String metricName() {
        return metricName;
    }
}
