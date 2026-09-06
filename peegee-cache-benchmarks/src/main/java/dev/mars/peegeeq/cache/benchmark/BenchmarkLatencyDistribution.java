package dev.mars.peegeeq.cache.benchmark;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;

/**
 * Immutable disjoint latency buckets: [0,b0], (b0,b1], ... and explicit overflow.
 * Percentiles are nearest-rank bucket upper bounds, not exact sample latencies.
 */
public record BenchmarkLatencyDistribution(List<Long> upperBoundsNanos, List<Long> counts, long overflowCount) {
    public static final int MAX_BUCKETS = 65_536;

    public BenchmarkLatencyDistribution {
        upperBoundsNanos = List.copyOf(upperBoundsNanos);
        counts = List.copyOf(counts);
        if (upperBoundsNanos.isEmpty() || upperBoundsNanos.size() > MAX_BUCKETS
                || counts.size() != upperBoundsNanos.size() || overflowCount < 0) {
            throw new IllegalArgumentException("Distribution requires bounded, matching buckets and non-negative overflow");
        }
        long previous = -1;
        long total = overflowCount;
        try {
            for (int index = 0; index < counts.size(); index++) {
                long bound = upperBoundsNanos.get(index);
                if (bound <= previous || counts.get(index) < 0) {
                    throw new IllegalArgumentException("Bounds must increase from zero or above; counts must be non-negative");
                }
                previous = bound;
                total = Math.addExact(total, counts.get(index));
            }
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("Sample count exceeds signed 64-bit range", overflow);
        }
    }

    public long sampleCount() {
        long total = overflowCount;
        for (long count : counts) total += count;
        return total;
    }

    /** Empty means no samples or the requested rank lies in the unbounded overflow bucket. */
    public OptionalLong percentileUpperBound(double quantile) {
        if (!Double.isFinite(quantile) || quantile <= 0 || quantile > 1) {
            throw new IllegalArgumentException("Quantile must be finite and in (0,1]");
        }
        long total = sampleCount();
        if (total == 0) return OptionalLong.empty();
        long rank = BigDecimal.valueOf(quantile).multiply(BigDecimal.valueOf(total))
                .setScale(0, RoundingMode.CEILING).longValueExact();
        long cumulative = 0;
        for (int index = 0; index < counts.size(); index++) {
            cumulative += counts.get(index);
            if (cumulative >= rank) return OptionalLong.of(upperBoundsNanos.get(index));
        }
        return OptionalLong.empty();
    }

    public BenchmarkLatencyDistribution merge(BenchmarkLatencyDistribution other) {
        if (!upperBoundsNanos.equals(other.upperBoundsNanos)) {
            throw new IllegalArgumentException("Only matching bucket layouts can be merged");
        }
        try {
            var merged = new ArrayList<Long>(counts.size());
            for (int index = 0; index < counts.size(); index++) {
                merged.add(Math.addExact(counts.get(index), other.counts.get(index)));
            }
            return new BenchmarkLatencyDistribution(upperBoundsNanos, merged, Math.addExact(overflowCount, other.overflowCount));
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("Merged count exceeds signed 64-bit range", overflow);
        }
    }

    public JsonObject toJson() {
        return new JsonObject().put("unit", "nanoseconds").put("upperBoundsNanos", new JsonArray(upperBoundsNanos))
                .put("counts", new JsonArray(counts)).put("overflowCount", overflowCount).put("sampleCount", sampleCount())
                .put("bucketSemantics", "[0,b0],(b0,b1],...,overflow")
                .put("percentileRepresentation", "BUCKET_UPPER_BOUND")
                .put("p50UpperBoundNanos", nullableBound(.5)).put("p95UpperBoundNanos", nullableBound(.95))
                .put("p99UpperBoundNanos", nullableBound(.99))
                .put("missingPercentileReason", sampleCount() == 0 ? "NO_SAMPLES" : overflowCount > 0 ? "RANK_MAY_EXCEED_RANGE" : "");
    }

    private Long nullableBound(double quantile) {
        var value = percentileUpperBound(quantile);
        return value.isPresent() ? value.getAsLong() : null;
    }
}
