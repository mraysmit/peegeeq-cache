package dev.mars.peegeeq.cache.rest.security;

public final class ManagementRateLimitException extends RuntimeException {

    public ManagementRateLimitException() {
        super("Management request rate limit exceeded");
    }

    public int status() {
        return 429;
    }

    public String code() {
        return "RATE_LIMITED";
    }
}
