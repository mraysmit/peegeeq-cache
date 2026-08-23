package dev.mars.peegeeq.cache.api.management;

import dev.mars.peegeeq.cache.api.exception.CacheException;

import java.util.Objects;

/** Typed counter-mutation failure whose rejected statement leaves state unchanged. */
public final class ManagementCounterException extends CacheException {

    public enum Code {
        OVERFLOW
    }

    private final Code code;

    public ManagementCounterException(Code code, Throwable cause) {
        super("Management counter mutation failed: " + Objects.requireNonNull(code, "code"), cause);
        this.code = code;
    }

    public Code code() {
        return code;
    }
}
