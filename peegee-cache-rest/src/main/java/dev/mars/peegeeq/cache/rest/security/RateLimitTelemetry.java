package dev.mars.peegeeq.cache.rest.security;

@FunctionalInterface
public interface RateLimitTelemetry {
    void record(RateLimitAction action, RateLimitOutcome outcome);

    static RateLimitTelemetry noop() {
        return (action, outcome) -> { };
    }
}
