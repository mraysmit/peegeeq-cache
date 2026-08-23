package dev.mars.peegeeq.cache.rest.server;

/** Safe typed failure for process-local management pub/sub resources. */
public final class PubSubManagementException extends RuntimeException {

    public enum Code {
        NOT_FOUND,
        LIMIT_REACHED,
        EXPIRED
    }

    private final Code code;

    public PubSubManagementException(Code code) {
        super("Management pub/sub operation rejected: " + code);
        this.code = java.util.Objects.requireNonNull(code, "code");
    }

    public Code code() {
        return code;
    }
}
