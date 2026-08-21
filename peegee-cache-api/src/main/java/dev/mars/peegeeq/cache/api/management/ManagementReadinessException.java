package dev.mars.peegeeq.cache.api.management;

import dev.mars.peegeeq.cache.api.exception.CacheException;

import java.util.Objects;

/** Typed failure indicating that the PostgreSQL management read model is not ready. */
public final class ManagementReadinessException extends CacheException {

    public enum Code {
        SCHEMA_UNAVAILABLE
    }

    private final Code code;

    public ManagementReadinessException(Code code, Throwable cause) {
        super("Management read model is unavailable: " + Objects.requireNonNull(code, "code"), cause);
        this.code = code;
    }

    public Code code() {
        return code;
    }
}
