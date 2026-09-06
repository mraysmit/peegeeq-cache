package dev.mars.peegeeq.cache.benchmark;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Immutable planned phase boundaries, independent of wall clocks and workload execution. */
public final class BenchmarkTimeline {
    public enum PhaseKind { WARMUP, BASELINE, TRANSITION, LOAD, SUSTAINED, RECOVERY, DRAIN }

    /** Names identify individual phases, including repeated steps of the same kind. */
    public record Phase(String name, PhaseKind kind, Duration duration) {
        public Phase {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(kind, "kind");
            if (name.isBlank()) throw new IllegalArgumentException("Phase name must not be blank");
            positiveNanos(duration, "phase duration");
        }
    }

    /** Half-open planned interval [startNanos, endNanos), relative to experiment start. */
    public record Window(long index, Phase phase, long startNanos, long endNanos, boolean partial) {
        public Window {
            Objects.requireNonNull(phase, "phase");
            if (index < 0 || startNanos < 0 || endNanos <= startNanos) {
                throw new IllegalArgumentException("Window index and boundaries must be valid");
            }
        }
    }

    private final List<Phase> phases;
    private final long samplingNanos;
    private final long durationNanos;
    private final long windowCount;

    public BenchmarkTimeline(List<Phase> phases, Duration samplingInterval) {
        this.phases = List.copyOf(phases);
        samplingNanos = positiveNanos(samplingInterval, "sampling interval");
        if (this.phases.isEmpty()) throw new IllegalArgumentException("At least one phase is required");
        var names = new HashSet<String>();
        long duration = 0;
        long windows = 0;
        try {
            for (Phase phase : this.phases) {
                if (!names.add(phase.name())) throw new IllegalArgumentException("Phase names must be unique");
                long nanos = phase.duration().toNanos();
                duration = Math.addExact(duration, nanos);
                windows = Math.addExact(windows, 1 + (nanos - 1) / samplingNanos);
            }
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("Timeline exceeds nanosecond range", overflow);
        }
        durationNanos = duration;
        windowCount = windows;
    }

    public List<Phase> phases() { return phases; }
    public Duration duration() { return Duration.ofNanos(durationNanos); }
    public Duration samplingInterval() { return Duration.ofNanos(samplingNanos); }
    public long windowCount() { return windowCount; }

    /** Resolves lazily: memory grows with phases, never with the number of sampling windows. */
    public Optional<Window> windowAt(long elapsedNanos) {
        if (elapsedNanos < 0) throw new IllegalArgumentException("Elapsed time must not be negative");
        if (elapsedNanos >= durationNanos) return Optional.empty();
        long phaseStart = 0;
        long firstWindow = 0;
        for (Phase phase : phases) {
            long nanos = phase.duration().toNanos();
            long phaseEnd = phaseStart + nanos;
            if (elapsedNanos < phaseEnd) {
                long withinPhase = (elapsedNanos - phaseStart) / samplingNanos;
                long start = phaseStart + withinPhase * samplingNanos;
                long length = Math.min(samplingNanos, phaseEnd - start);
                return Optional.of(new Window(firstWindow + withinPhase, phase, start,
                        start + length, length < samplingNanos));
            }
            phaseStart = phaseEnd;
            firstWindow += 1 + (nanos - 1) / samplingNanos;
        }
        throw new IllegalStateException("Timeline did not contain an in-range offset");
    }

    private static long positiveNanos(Duration duration, String field) {
        Objects.requireNonNull(duration, field);
        try {
            long nanos = duration.toNanos();
            if (nanos <= 0) throw new IllegalArgumentException(field + " must be positive");
            return nanos;
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException(field + " exceeds nanosecond range", overflow);
        }
    }
}
