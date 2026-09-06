package dev.mars.peegeeq.cache.benchmark;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * Bounded, synchronised interval accounting for one scenario. Clock values are elapsed nanoseconds
 * read inside the lock. The recorder owns no threads, timers, request objects or retained history.
 * Request identity and suppression of duplicate/late terminal callbacks belong to the scheduler.
 */
public final class BenchmarkIntervalRecorder {
    public enum Outcome { SUCCESS, FAILURE, TIMEOUT }
    public static final List<String> SERIES = List.of("successfulService", "successfulEndToEnd",
            "failedService", "failedEndToEnd", "timedOutService", "timedOutEndToEnd");

    public record Sample(BenchmarkInterval interval, Map<String, BenchmarkLatencyDistribution> distributions) {
        public Sample { distributions = Map.copyOf(distributions); }
    }

    private final List<Long> bounds;
    private final long[] bucketBounds;
    private final LongSupplier clock;
    private final long[][] buckets;
    private final long[] totals = new long[8];
    private BenchmarkWorkCounts before = BenchmarkWorkCounts.zero();
    private long startNanos;
    private long lastEvent = -1;

    public BenchmarkIntervalRecorder(List<Long> upperBoundsNanos, LongSupplier elapsedNanos) {
        bounds = List.copyOf(upperBoundsNanos);
        new BenchmarkLatencyDistribution(bounds, Collections.nCopies(bounds.size(), 0L), 0);
        bucketBounds = bounds.stream().mapToLong(Long::longValue).toArray();
        clock = Objects.requireNonNull(elapsedNanos, "elapsedNanos");
        startNanos = clock.getAsLong();
        if (startNanos < 0) throw new IllegalArgumentException("Elapsed clock must be non-negative");
        buckets = new long[SERIES.size()][bounds.size() + 1];
    }

    public synchronized void schedule() { increment(0); }
    public synchronized void admit() { increment(1); }
    public synchronized void start() { increment(2); }
    public synchronized void reject() { increment(6); }
    public synchronized void expireBeforeStart() { increment(7); }

    /** Accounts for generator misses in constant space/time, without constructing request objects. */
    synchronized void rejectScheduled(long count) {
        if (count <= 0) throw new IllegalArgumentException("Rejected scheduled count must be positive");
        long at = eventTime();
        long scheduled = Math.addExact(totals[0], count);
        long rejected = Math.addExact(totals[6], count);
        totals[0] = scheduled;
        totals[6] = rejected;
        lastEvent = at;
    }

    public synchronized void complete(Outcome outcome, long serviceNanos, long endToEndNanos) {
        Objects.requireNonNull(outcome, "outcome");
        if (serviceNanos < 0 || endToEndNanos < serviceNanos) {
            throw new IllegalArgumentException("Latencies must be non-negative and end-to-end must include service time");
        }
        long at = eventTime();
        int field = 3 + outcome.ordinal();
        validateIncrement(field);
        int series = outcome.ordinal() * 2;
        // Totals cannot overflow: each series is bounded by its already-validated cumulative outcome count.
        buckets[series][bucket(serviceNanos)]++;
        buckets[series + 1][bucket(endToEndNanos)]++;
        totals[field]++;
        lastEvent = at;
    }

    /**
     * Closes [start,end) at the actual clock reading and clears only interval distributions.
     * A non-advancing clock or a boundary equal to the last event is rejected without mutation;
     * the caller can retry after time advances. Events are never moved across a half-open boundary.
     */
    public synchronized Sample checkpoint() {
        long end = clock.getAsLong();
        if (end <= startNanos || end <= lastEvent) {
            throw new IllegalArgumentException("Checkpoint must be after the last event and interval start");
        }
        var distributions = new LinkedHashMap<String, BenchmarkLatencyDistribution>();
        for (int series = 0; series < SERIES.size(); series++) {
            var values = new ArrayList<Long>(bounds.size());
            for (int bucket = 0; bucket < bounds.size(); bucket++) values.add(buckets[series][bucket]);
            distributions.put(SERIES.get(series), new BenchmarkLatencyDistribution(bounds, values, buckets[series][bounds.size()]));
        }
        var counts = new BenchmarkWorkCounts(totals[0], totals[1], totals[2], totals[3], totals[4], totals[5], totals[6], totals[7]);
        var sample = new Sample(new BenchmarkInterval(startNanos, end, before, counts), distributions);
        for (var series : buckets) Arrays.fill(series, 0);
        before = counts;
        startNanos = end;
        return sample;
    }

    private void increment(int field) {
        long at = eventTime();
        validateIncrement(field);
        totals[field]++;
        lastEvent = at;
    }

    private void validateIncrement(int field) {
        long available = switch (field) {
            case 0 -> Long.MAX_VALUE - totals[0];
            case 1, 6 -> totals[0] - totals[1] - totals[6];
            case 2, 7 -> totals[1] - totals[2] - totals[7];
            case 3, 4, 5 -> totals[2] - totals[3] - totals[4] - totals[5];
            default -> throw new IllegalArgumentException("Unknown counter field");
        };
        if (available <= 0) throw new IllegalArgumentException("Request event exceeds its available population");
    }

    private long eventTime() {
        long at = clock.getAsLong();
        if (at < startNanos || at < lastEvent) throw new IllegalArgumentException("Elapsed clock regressed");
        return at;
    }

    private int bucket(long latency) {
        int found = Arrays.binarySearch(bucketBounds, latency);
        return found >= 0 ? found : -found - 1;
    }
}
