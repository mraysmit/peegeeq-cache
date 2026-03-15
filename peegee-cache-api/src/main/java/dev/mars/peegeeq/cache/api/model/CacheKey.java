package dev.mars.peegeeq.cache.api.model;

import java.util.Objects;

public record CacheKey(String namespace, String key) {

    public CacheKey {
        Objects.requireNonNull(namespace, "namespace must not be null");
        Objects.requireNonNull(key, "key must not be null");

        if (namespace.isBlank()) {
            throw new IllegalArgumentException("namespace must not be blank");
        }
        if (key.isBlank()) {
            throw new IllegalArgumentException("key must not be blank");
        }
    }

    public String asQualifiedKey() {
        return namespace + ":" + key;
    }
}
