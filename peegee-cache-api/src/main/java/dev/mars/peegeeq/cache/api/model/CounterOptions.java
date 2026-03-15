package dev.mars.peegeeq.cache.api.model;

import java.time.Duration;

public record CounterOptions(
        Duration ttl,
        boolean createIfMissing,
        CounterTtlMode ttlMode
) {
    public static CounterOptions defaults() {
        return new CounterOptions(null, true, CounterTtlMode.PRESERVE_EXISTING);
    }
}
