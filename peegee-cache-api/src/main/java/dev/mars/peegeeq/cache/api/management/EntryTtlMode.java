package dev.mars.peegeeq.cache.api.management;

/** Atomic TTL behavior for management cache writes. */
public enum EntryTtlMode {
    PRESERVE_EXISTING,
    USE_DEFAULT,
    REPLACE,
    REMOVE
}
