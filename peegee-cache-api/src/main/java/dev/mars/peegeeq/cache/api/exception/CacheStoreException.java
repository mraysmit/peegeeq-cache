package dev.mars.peegeeq.cache.api.exception;

import java.util.Objects;

public class CacheStoreException extends CacheException {
    public CacheStoreException(String message, Throwable cause) {
        super(message, Objects.requireNonNull(cause, "cause must not be null"));
    }
}
