package dev.mars.peegeeq.cache.api.management;

/** Exact, mutually exclusive TTL distribution buckets for management summaries. */
public enum ManagementTtlBucket {
    PERSISTENT,
    EXPIRED,
    LT_1_MINUTE,
    FROM_1_TO_5_MINUTES,
    FROM_5_TO_30_MINUTES,
    FROM_30_TO_60_MINUTES,
    GTE_60_MINUTES
}
