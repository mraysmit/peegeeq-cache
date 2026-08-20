package dev.mars.peegeeq.cache.core.writebehind;

import dev.mars.peegeeq.cache.api.model.CacheKey;
import dev.mars.peegeeq.cache.api.model.CacheSetRequest;

import java.util.Objects;

/** A most-recent cache mutation waiting to be flushed. */
public record PendingWrite(
        CacheKey key,
        Operation operation,
        CacheSetRequest request,
        long acceptedAtNanos
) {

    public enum Operation {
        SET,
        DELETE
    }

    public PendingWrite {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(operation, "operation");
        if (operation == Operation.SET) {
            Objects.requireNonNull(request, "request must be present for SET");
            if (!key.equals(request.key())) {
                throw new IllegalArgumentException("request key must match pending write key");
            }
        } else if (request != null) {
            throw new IllegalArgumentException("request must be null for DELETE");
        }
    }

    public static PendingWrite set(CacheSetRequest request, long acceptedAtNanos) {
        Objects.requireNonNull(request, "request");
        return new PendingWrite(
                Objects.requireNonNull(request.key(), "request.key"),
                Operation.SET,
                request,
                acceptedAtNanos);
    }

    public static PendingWrite delete(CacheKey key, long acceptedAtNanos) {
        return new PendingWrite(key, Operation.DELETE, null, acceptedAtNanos);
    }
}
