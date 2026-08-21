package dev.mars.peegeeq.cache.api.management;

/** Management-list TTL filter, distinct from the core single-key TTL result state. */
public enum ManagementTtlFilter {
    ALL_LIVE,
    PERSISTENT,
    EXPIRING,
    INCLUDE_EXPIRED
}
