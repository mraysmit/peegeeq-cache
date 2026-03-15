package dev.mars.peegeeq.cache.api.model;

public enum SetMode {
    UPSERT,
    ONLY_IF_ABSENT,
    ONLY_IF_PRESENT,
    ONLY_IF_VERSION_MATCHES
}
