package dev.mars.peegeeq.cache.rest.security;

import java.util.Objects;

/**
 * Authentication failure whose public message never contains supplied identity values.
 */
public final class ManagementAuthenticationException extends RuntimeException {

    private final int status;
    private final String code;

    public ManagementAuthenticationException(int status, String code, String message) {
        super(Objects.requireNonNull(message, "message"));
        if (status != 401) {
            throw new IllegalArgumentException("Authentication failures must use HTTP status 401");
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
