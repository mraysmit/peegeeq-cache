package dev.mars.peegeeq.cache.api.management;

import dev.mars.peegeeq.cache.api.exception.CacheException;

import java.util.Objects;

/** Safe typed rejection for actor-bound bulk-delete preview tokens and scopes. */
public final class ManagementBulkDeleteException extends CacheException {

    public enum Code {
        TOKEN_INVALID,
        TOKEN_EXPIRED,
        SCOPE_MISMATCH,
        CONFIRMATION_MISMATCH,
        SCOPE_TOO_LARGE
    }

    private final Code code;

    public ManagementBulkDeleteException(Code code) {
        super("Management bulk delete rejected: " + Objects.requireNonNull(code, "code"));
        this.code = code;
    }

    public Code code() {
        return code;
    }
}
