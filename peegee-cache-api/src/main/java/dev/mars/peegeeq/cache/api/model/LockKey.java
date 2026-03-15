package dev.mars.peegeeq.cache.api.model;

public record LockKey(String namespace, String key) {

    public LockKey {
        if (namespace == null || namespace.isBlank()) {
            throw new IllegalArgumentException("namespace must not be blank");
        }
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("key must not be blank");
        }
    }

    public String asQualifiedKey() {
        return namespace + ":" + key;
    }
}
