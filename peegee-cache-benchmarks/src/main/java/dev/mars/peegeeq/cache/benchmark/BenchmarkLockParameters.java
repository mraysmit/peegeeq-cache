package dev.mars.peegeeq.cache.benchmark;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Immutable lock-cardinality, contention, lease and fencing controls. */
public record BenchmarkLockParameters(int lockCardinality, int hotLockCount,
                                      double hotLockRequestFraction, Duration leaseTtl,
                                      boolean fencing) {
    public BenchmarkLockParameters {
        Objects.requireNonNull(leaseTtl, "leaseTtl");
        long leaseNanos;
        try {
            leaseNanos = leaseTtl.toNanos();
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("Lock lease exceeds nanosecond range", overflow);
        }
        if (lockCardinality < 2 || lockCardinality > 1_000_000
                || hotLockCount <= 0 || hotLockCount >= lockCardinality
                || !Double.isFinite(hotLockRequestFraction)
                || hotLockRequestFraction <= 0 || hotLockRequestFraction > 1
                || leaseNanos < 1_000_000 || leaseNanos % 1_000_000 != 0) {
            throw new IllegalArgumentException("Invalid lock cardinality, hot set, fraction or lease");
        }
    }

    public long lockIndex(long seed, long logicalRequestId) {
        long mixed = mix64(seed ^ mix64(logicalRequestId));
        double choice = (mix64(mixed) >>> 11) * 0x1.0p-53;
        if (choice < hotLockRequestFraction) return bounded(mixed, hotLockCount);
        return hotLockCount + bounded(mixed, lockCardinality - hotLockCount);
    }

    public Map<String, String> environment() {
        var values = new LinkedHashMap<String, String>();
        values.put("scenario.lockCardinality", Integer.toString(lockCardinality));
        values.put("scenario.hotLockCount", Integer.toString(hotLockCount));
        values.put("scenario.hotLockRequestFraction", Double.toString(hotLockRequestFraction));
        values.put("scenario.lockLeaseMillis", Long.toString(leaseTtl.toMillis()));
        values.put("scenario.lockFencing", Boolean.toString(fencing));
        return Map.copyOf(values);
    }

    private static long bounded(long value, int bound) {
        return Long.remainderUnsigned(value, bound);
    }

    private static long mix64(long value) {
        value = (value ^ (value >>> 30)) * 0xbf58476d1ce4e5b9L;
        value = (value ^ (value >>> 27)) * 0x94d049bb133111ebL;
        return value ^ (value >>> 31);
    }
}
