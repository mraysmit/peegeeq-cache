package dev.mars.peegeeq.cache.rest.server;

import java.util.Objects;

public final class SetupRegistryException extends RuntimeException {

    private final int status;
    private final String code;

    public SetupRegistryException(int status, String code, String message) {
        super(Objects.requireNonNull(message, "message"));
        this.status = status;
        this.code = Objects.requireNonNull(code, "code");
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }
}
