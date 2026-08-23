package dev.mars.peegeeq.cache.rest.security;

import java.util.Objects;

public final class ManagementSecurityException extends RuntimeException {

    private final int status;
    private final String code;

    public ManagementSecurityException(int status, String code, String message) {
        super(Objects.requireNonNull(message, "message"));
        if (status != 403) {
            throw new IllegalArgumentException("Security policy failures must use HTTP status 403");
        }
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
