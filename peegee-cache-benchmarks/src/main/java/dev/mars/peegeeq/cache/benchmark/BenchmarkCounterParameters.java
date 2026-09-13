package dev.mars.peegeeq.cache.benchmark;

import java.util.LinkedHashMap;
import java.util.Map;

/** Immutable counter-cardinality, contention and correctness parameters. */
public record BenchmarkCounterParameters(int counterCardinality, int hotCounterCount,
                                         double hotCounterRequestFraction, long delta,
                                         long initialValue) {
    public BenchmarkCounterParameters {
        if (counterCardinality < 2 || counterCardinality > 1_000_000
                || hotCounterCount <= 0 || hotCounterCount >= counterCardinality
                || !Double.isFinite(hotCounterRequestFraction)
                || hotCounterRequestFraction <= 0 || hotCounterRequestFraction > 1
                || delta <= 0) {
            throw new IllegalArgumentException("Invalid counter cardinality, hot set, fraction or delta");
        }
        try {
            Math.multiplyExact(initialValue, counterCardinality);
            Math.addExact(initialValue, delta);
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("Counter configuration exceeds the exact long range", overflow);
        }
    }

    public long counterIndex(long seed, long logicalRequestId) {
        long mixed = mix64(seed ^ mix64(logicalRequestId));
        double choice = (mix64(mixed) >>> 11) * 0x1.0p-53;
        if (choice < hotCounterRequestFraction) return bounded(mixed, hotCounterCount);
        return hotCounterCount + bounded(mixed, counterCardinality - hotCounterCount);
    }

    public Map<String, String> environment() {
        var values = new LinkedHashMap<String, String>();
        values.put("scenario.counterCardinality", Integer.toString(counterCardinality));
        values.put("scenario.hotCounterCount", Integer.toString(hotCounterCount));
        values.put("scenario.hotCounterRequestFraction", Double.toString(hotCounterRequestFraction));
        values.put("scenario.counterDelta", Long.toString(delta));
        values.put("scenario.counterInitialValue", Long.toString(initialValue));
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
