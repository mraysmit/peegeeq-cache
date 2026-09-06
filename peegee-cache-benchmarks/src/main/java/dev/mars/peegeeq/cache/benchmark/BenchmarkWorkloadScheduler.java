package dev.mars.peegeeq.cache.benchmark;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * Pull-driven, bounded workload state machine. The caller dispatches every returned launch exactly
 * once and reports its physical completion, including failures to dispatch. It owns the clock driver,
 * transport and shutdown; this class owns no threads, timers or transport cancellation.
 *
 * <p>Rate-controlled arrivals follow an absolute rational schedule, independent of completion.
 * On a delayed advance, the oldest arrivals exceeding the configured catch-up cap are counted as
 * generator misses (scheduled + rejected); only the newest bounded tail is considered for admission.
 * Closed-loop clients refill on the next advance after physical completion, never at logical timeout.
 * Checkpoint rollover is explicit and must occur strictly after the last accounting event.
 */
public final class BenchmarkWorkloadScheduler {
    public record Launch(long id, long scheduledNanos, long startedNanos) { }

    /** Exact cumulative counters and maxima since construction; queue/physical populations are current. */
    public record Statistics(long generatorMissed, long admissionRejected, long lateSucceeded,
                             long lateFailed, long duplicateCompletions, int physicalInFlight, int queued,
                             long maximumScheduleLagNanos, long maximumDeadlineDetectionLagNanos) {
        /** Cumulative diagnostics/current populations, not per-interval deltas or throughput rates. */
        public Map<String, BenchmarkRunEvidence.Metric> metrics() {
            return Map.of("scheduler.generatorMissed", metric("count", generatorMissed),
                    "scheduler.admissionRejected", metric("count", admissionRejected),
                    "scheduler.lateSucceeded", metric("count", lateSucceeded),
                    "scheduler.lateFailed", metric("count", lateFailed),
                    "scheduler.duplicateCompletions", metric("count", duplicateCompletions),
                    "scheduler.physicalInFlight", metric("count", physicalInFlight),
                    "scheduler.queued", metric("count", queued),
                    "scheduler.maximumScheduleLag", metric("ns", maximumScheduleLagNanos),
                    "scheduler.maximumDeadlineDetectionLag", metric("ns", maximumDeadlineDetectionLagNanos));
        }

        private static BenchmarkRunEvidence.Metric metric(String unit, long value) {
            // The generic metric schema is binary64; keep an exact decimal explanation outside its safe range.
            return value <= (1L << 53) ? new BenchmarkRunEvidence.Metric(unit, (double) value, "")
                    : new BenchmarkRunEvidence.Metric(unit, null,
                            "Exact integer " + value + " exceeds binary64 integer precision");
        }
    }

    private static final BigInteger BILLION = BigInteger.valueOf(1_000_000_000);
    private final BenchmarkParameters parameters;
    private final long durationNanos;
    private final long timeoutNanos;
    private final int maximumArrivalsPerAdvance;
    private final LongSupplier clock;
    private final long origin;
    private final BigInteger numerator;
    private final BigInteger denominator;
    private final long plannedArrivals;
    private final BenchmarkIntervalRecorder recorder;
    private final ArrayDeque<Long> queue = new ArrayDeque<>();
    private final Map<Long, Active> active = new LinkedHashMap<>();
    private long eventNanos;
    private long arrivals;
    private long nextId;
    private long missed;
    private long rejected;
    private long lateSucceeded;
    private long lateFailed;
    private long duplicates;
    private long maximumScheduleLag;
    private long maximumDeadlineLag;

    private static final class Active {
        final Launch launch;
        final long deadline;
        boolean timedOut;

        Active(Launch launch, long deadline) {
            this.launch = launch;
            this.deadline = deadline;
        }
    }

    public BenchmarkWorkloadScheduler(BenchmarkParameters parameters, Duration duration,
                                      int maximumArrivalsPerAdvance, List<Long> latencyBoundsNanos,
                                      LongSupplier monotonicNanos) {
        this.parameters = Objects.requireNonNull(parameters, "parameters");
        Objects.requireNonNull(duration, "duration");
        timeoutNanos = parameters.operationTimeout().toNanos();
        try {
            durationNanos = duration.toNanos();
            if (durationNanos <= 0) throw new IllegalArgumentException("Duration must be positive");
            Math.addExact(durationNanos, timeoutNanos);
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("Duration and deadline exceed the nanosecond range", overflow);
        }
        if (maximumArrivalsPerAdvance <= 0) throw new IllegalArgumentException("Arrival cap must be positive");
        this.maximumArrivalsPerAdvance = maximumArrivalsPerAdvance;
        clock = Objects.requireNonNull(monotonicNanos, "monotonicNanos");
        origin = clock.getAsLong();
        if (parameters.loadModel() == BenchmarkParameters.LoadModel.RATE_CONTROLLED) {
            // Preserve the configured decimal rate, rather than rounding a period and changing demand.
            var rate = BigDecimal.valueOf(parameters.offeredPerSecond());
            numerator = rate.unscaledValue().multiply(BigInteger.TEN.pow(Math.max(0, -rate.scale())));
            denominator = BILLION.multiply(BigInteger.TEN.pow(Math.max(0, rate.scale())));
            try {
                plannedArrivals = ceil(BigInteger.valueOf(durationNanos).multiply(numerator), denominator).longValueExact();
            } catch (ArithmeticException overflow) {
                throw new IllegalArgumentException("Planned arrivals exceed the counter range", overflow);
            }
        } else {
            numerator = BigInteger.ZERO;
            denominator = BigInteger.ONE;
            plannedArrivals = 0;
        }
        recorder = new BenchmarkIntervalRecorder(latencyBoundsNanos, () -> eventNanos);
    }

    /** Advances deadlines and demand; the returned list is bounded by configured physical concurrency. */
    public synchronized List<Launch> advance() {
        long now = now();
        expire(now);
        var launches = new ArrayList<Launch>();
        while (!queue.isEmpty() && active.size() < parameters.concurrency()) {
            launch(queue.removeFirst(), now, launches);
        }
        if (parameters.loadModel() == BenchmarkParameters.LoadModel.CLOSED_LOOP) {
            if (now < durationNanos) {
                int available = parameters.concurrency() - active.size();
                for (int index = 0; index < available; index++) {
                    recorder.schedule();
                    recorder.admit();
                    launch(now, now, launches);
                }
            }
        } else {
            arrive(now, launches);
        }
        return List.copyOf(launches);
    }

    private void arrive(long now, List<Launch> launches) {
        long due = now >= durationNanos ? plannedArrivals
                : BigInteger.valueOf(now).multiply(numerator).divide(denominator).add(BigInteger.ONE)
                        .min(BigInteger.valueOf(plannedArrivals)).longValueExact();
        long pending = due - arrivals;
        if (pending == 0) return;
        maximumScheduleLag = Math.max(maximumScheduleLag, now - scheduledAt(arrivals));
        long skip = now >= durationNanos ? pending : Math.max(0, pending - maximumArrivalsPerAdvance);
        if (skip > 0) {
            recorder.rejectScheduled(skip);
            missed = Math.addExact(missed, skip);
            arrivals += skip;
        }
        while (arrivals < due) {
            long scheduled = scheduledAt(arrivals++);
            recorder.schedule();
            if (now >= scheduled + timeoutNanos) {
                recorder.admit();
                recorder.expireBeforeStart();
                maximumDeadlineLag = Math.max(maximumDeadlineLag, now - scheduled - timeoutNanos);
            } else if (active.size() < parameters.concurrency()) {
                recorder.admit();
                launch(scheduled, now, launches);
            } else if (queue.size() < parameters.queueCapacity()) {
                recorder.admit();
                queue.addLast(scheduled);
            } else {
                recorder.reject();
                rejected++;
            }
        }
    }

    private long scheduledAt(long index) {
        return ceil(BigInteger.valueOf(index).multiply(denominator), numerator).longValueExact();
    }

    private static BigInteger ceil(BigInteger value, BigInteger divisor) {
        return value.add(divisor).subtract(BigInteger.ONE).divide(divisor);
    }

    private void launch(long scheduled, long now, List<Launch> launches) {
        long id = nextId;
        nextId = Math.incrementExact(nextId);
        recorder.start();
        var launch = new Launch(id, scheduled, now);
        active.put(id, new Active(launch, scheduled + timeoutNanos));
        launches.add(launch);
    }

    /**
     * Records one physical success/failure. A completion at or beyond its deadline is a logical
     * timeout even if advance has not detected it yet. Duplicate completions are ignored and counted;
     * never-issued IDs are rejected. A timeout does not imply transport cancellation.
     */
    public synchronized boolean complete(long id, boolean succeeded) {
        if (id < 0 || id >= nextId) throw new IllegalArgumentException("Unknown launch ID");
        long now = now();
        expire(now);
        var request = active.remove(id);
        if (request == null) {
            duplicates = Math.incrementExact(duplicates);
            return false;
        }
        if (request.timedOut) {
            if (succeeded) lateSucceeded++; else lateFailed++;
        } else {
            recorder.complete(succeeded ? BenchmarkIntervalRecorder.Outcome.SUCCESS : BenchmarkIntervalRecorder.Outcome.FAILURE,
                    now - request.launch.startedNanos(), now - request.launch.scheduledNanos());
        }
        return true;
    }

    private void expire(long now) {
        for (var request : active.values()) {
            if (!request.timedOut && now >= request.deadline) {
                recorder.complete(BenchmarkIntervalRecorder.Outcome.TIMEOUT,
                        request.deadline - request.launch.startedNanos(), timeoutNanos);
                request.timedOut = true;
                maximumDeadlineLag = Math.max(maximumDeadlineLag, now - request.deadline);
            }
        }
        while (!queue.isEmpty() && now >= queue.getFirst() + timeoutNanos) {
            long deadline = queue.removeFirst() + timeoutNanos;
            recorder.expireBeforeStart();
            maximumDeadlineLag = Math.max(maximumDeadlineLag, now - deadline);
        }
    }

    public synchronized BenchmarkIntervalRecorder.Sample checkpoint() {
        now();
        return recorder.checkpoint();
    }

    public synchronized Statistics statistics() {
        return new Statistics(missed, rejected, lateSucceeded, lateFailed, duplicates, active.size(), queue.size(),
                maximumScheduleLag, maximumDeadlineLag);
    }

    /** True only after demand ends and all admitted physical work has actually completed. */
    public synchronized boolean drained() {
        return eventNanos >= durationNanos && queue.isEmpty() && active.isEmpty()
                && (parameters.loadModel() == BenchmarkParameters.LoadModel.CLOSED_LOOP || arrivals == plannedArrivals);
    }

    private long now() {
        long value = Math.subtractExact(clock.getAsLong(), origin);
        if (value < eventNanos) throw new IllegalArgumentException("Monotonic clock regressed");
        eventNanos = value;
        return value;
    }
}
