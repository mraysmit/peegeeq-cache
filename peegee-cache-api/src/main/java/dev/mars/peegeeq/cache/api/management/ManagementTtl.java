package dev.mars.peegeeq.cache.api.management;

import java.time.Instant;
import java.util.Objects;

/** Management TTL snapshot with state-specific nullability invariants. */
public record ManagementTtl(State state, Long ttlMillis, Instant expiresAt) {

    public enum State {
        PERSISTENT,
        EXPIRING,
        EXPIRED
    }

    public ManagementTtl {
        Objects.requireNonNull(state, "state");
        switch (state) {
            case PERSISTENT -> {
                if (ttlMillis != null || expiresAt != null) {
                    throw new IllegalArgumentException("persistent TTL has no duration or expiry");
                }
            }
            case EXPIRING -> {
                if (ttlMillis == null || ttlMillis <= 0 || expiresAt == null) {
                    throw new IllegalArgumentException("expiring TTL requires positive duration and expiry");
                }
            }
            case EXPIRED -> {
                if (!Long.valueOf(0).equals(ttlMillis) || expiresAt == null) {
                    throw new IllegalArgumentException("expired TTL requires zero duration and expiry");
                }
            }
        }
    }

    public static ManagementTtl persistent() {
        return new ManagementTtl(State.PERSISTENT, null, null);
    }

    public static ManagementTtl expiring(long ttlMillis, Instant expiresAt) {
        return new ManagementTtl(State.EXPIRING, ttlMillis, expiresAt);
    }

    public static ManagementTtl expired(Instant expiresAt) {
        return new ManagementTtl(State.EXPIRED, 0L, expiresAt);
    }
}
