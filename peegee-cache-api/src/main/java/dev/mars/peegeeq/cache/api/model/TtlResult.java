package dev.mars.peegeeq.cache.api.model;

public record TtlResult(
        TtlState state,
        Long ttlMillis
) {
    public static TtlResult missing() {
        return new TtlResult(TtlState.KEY_MISSING, null);
    }

    public static TtlResult persistent() {
        return new TtlResult(TtlState.PERSISTENT, null);
    }

    public static TtlResult expiring(long ttlMillis) {
        return new TtlResult(TtlState.EXPIRING, ttlMillis);
    }
}
