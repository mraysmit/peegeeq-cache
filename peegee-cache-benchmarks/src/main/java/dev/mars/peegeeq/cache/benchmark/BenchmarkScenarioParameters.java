package dev.mars.peegeeq.cache.benchmark;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Immutable first B3 data/contention/runtime controls shared by product workload adapters. */
public record BenchmarkScenarioParameters(int datasetCardinality, int payloadBytes, double targetHitRatio,
                                          KeyDistribution keyDistribution, int hotSetSize,
                                          double hotRequestFraction, Duration ttl,
                                          TelemetryMode telemetryMode) {
    public enum KeyDistribution { UNIFORM, HOT_SET }
    public enum TelemetryMode { OFF, METRICS, TRACING, FULL }

    public BenchmarkScenarioParameters {
        if (datasetCardinality <= 0 || datasetCardinality > 10_000_000) {
            throw new IllegalArgumentException("Dataset cardinality must be between 1 and 10000000");
        }
        if (payloadBytes < 0 || payloadBytes > 16 * 1024 * 1024) {
            throw new IllegalArgumentException("Payload bytes must be between 0 and 16777216");
        }
        if (!Double.isFinite(targetHitRatio) || targetHitRatio < 0 || targetHitRatio > 1) {
            throw new IllegalArgumentException("Target hit ratio must be finite and in [0,1]");
        }
        Objects.requireNonNull(keyDistribution, "keyDistribution");
        Objects.requireNonNull(ttl, "ttl");
        Objects.requireNonNull(telemetryMode, "telemetryMode");
        long ttlNanos;
        try {
            ttlNanos = ttl.toNanos();
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("TTL exceeds nanosecond range", overflow);
        }
        if (ttlNanos < 0 || ttlNanos % 1_000_000 != 0) {
            throw new IllegalArgumentException("TTL must be zero or positive whole milliseconds");
        }
        if (keyDistribution == KeyDistribution.UNIFORM) {
            if (hotSetSize != datasetCardinality || Double.compare(hotRequestFraction, 0) != 0) {
                throw new IllegalArgumentException("Uniform distribution has no separate hot set");
            }
        } else if (hotSetSize <= 0 || hotSetSize >= datasetCardinality
                || !Double.isFinite(hotRequestFraction) || hotRequestFraction <= 0
                || hotRequestFraction > 1) {
            throw new IllegalArgumentException("Hot-set distribution requires a proper subset and fraction in (0,1]");
        }
    }

    public static BenchmarkScenarioParameters uniform(int datasetCardinality, int payloadBytes,
                                                       Duration ttl, TelemetryMode telemetryMode) {
        return uniform(datasetCardinality, payloadBytes, 1, ttl, telemetryMode);
    }

    public static BenchmarkScenarioParameters uniform(int datasetCardinality, int payloadBytes,
                                                       double targetHitRatio, Duration ttl,
                                                       TelemetryMode telemetryMode) {
        return new BenchmarkScenarioParameters(datasetCardinality, payloadBytes, targetHitRatio,
                KeyDistribution.UNIFORM,
                datasetCardinality, 0, ttl, telemetryMode);
    }

    /** Stable for a logical request; physical retries therefore address the same dataset entry. */
    public long keyIndex(long seed, long logicalRequestId) {
        long mixed = mix64(seed ^ mix64(logicalRequestId));
        if (keyDistribution == KeyDistribution.UNIFORM) return bounded(mixed, datasetCardinality);
        double choice = (mix64(mixed) >>> 11) * 0x1.0p-53;
        if (choice < hotRequestFraction) return bounded(mixed, hotSetSize);
        return hotSetSize + bounded(mixed, datasetCardinality - hotSetSize);
    }

    /** Exact-size deterministic binary payload; no per-attempt randomness enters the measured path. */
    public byte[] payload(long seed, long keyIndex) {
        if (keyIndex < 0 || keyIndex >= datasetCardinality) throw new IllegalArgumentException("Key index out of range");
        var bytes = new byte[payloadBytes];
        long state = mix64(seed ^ keyIndex);
        for (int offset = 0; offset < bytes.length; offset++) {
            if ((offset & 7) == 0) state = mix64(state + offset);
            bytes[offset] = (byte) (state >>> ((offset & 7) * 8));
        }
        return bytes;
    }

    public boolean expectsHit(long seed, long logicalRequestId) {
        if (targetHitRatio == 0) return false;
        if (targetHitRatio == 1) return true;
        double choice = (mix64(seed + mix64(logicalRequestId)) >>> 11) * 0x1.0p-53;
        return choice < targetHitRatio;
    }

    public Duration entryTtl() {
        return ttl.isZero() ? null : ttl;
    }

    /** Complete stable fields for merging into a run manifest before managed execution starts. */
    public Map<String, String> environment() {
        var values = new LinkedHashMap<String, String>();
        values.put("scenario.datasetCardinality", Integer.toString(datasetCardinality));
        values.put("scenario.payloadBytes", Integer.toString(payloadBytes));
        values.put("scenario.targetHitRatio", Double.toString(targetHitRatio));
        values.put("scenario.keyDistribution", keyDistribution.name());
        values.put("scenario.hotSetSize", Integer.toString(hotSetSize));
        values.put("scenario.hotRequestFraction", Double.toString(hotRequestFraction));
        values.put("scenario.ttlMillis", Long.toString(ttl.toMillis()));
        values.put("scenario.telemetryMode", telemetryMode.name());
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
