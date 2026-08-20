package dev.mars.peegeeq.cache.rest.protocol;

import java.util.Objects;

/** A typed protocol validation failure suitable for later problem-response mapping. */
public final class ManagementProtocolException extends IllegalArgumentException {

    private final int status;
    private final String code;

    public ManagementProtocolException(int status, String code, String safeDetail) {
        super(safeDetail);
        if (status < 400 || status > 599) {
            throw new IllegalArgumentException("status must be an HTTP error status");
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
