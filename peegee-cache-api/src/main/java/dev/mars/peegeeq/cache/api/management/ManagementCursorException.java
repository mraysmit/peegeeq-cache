package dev.mars.peegeeq.cache.api.management;

/** Typed failure raised when an opaque management cursor is invalid or out of scope. */
public final class ManagementCursorException extends IllegalArgumentException {

    public enum Code {
        INVALID_CURSOR,
        SCOPE_MISMATCH
    }

    private final Code code;

    public ManagementCursorException(Code code, String message) {
        super(message);
        this.code = java.util.Objects.requireNonNull(code, "code");
    }

    public Code code() {
        return code;
    }
}
