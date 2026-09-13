package dev.mars.peegeeq.cache.benchmark;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Immutable live load/workload schedule. Run parameters are safety ceilings; every non-drain phase
 * has one ordered profile and the final DRAIN phase generates no new work.
 */
public final class BenchmarkPhaseWorkloadPlan {
    public record Profile(String phase, int concurrency, double offeredPerSecond, BenchmarkWorkloadMix workload) {
        public Profile {
            Objects.requireNonNull(phase, "phase");
            Objects.requireNonNull(workload, "workload");
            if (phase.isBlank() || concurrency <= 0 || !Double.isFinite(offeredPerSecond)) {
                throw new IllegalArgumentException("Phase, concurrency and offered rate must be valid");
            }
        }
    }

    record LoadWindow(long startNanos, long endNanos, Profile profile) { }

    private final BenchmarkTimeline timeline;
    private final BenchmarkParameters ceiling;
    private final List<Profile> profiles;
    private final List<LoadWindow> windows;
    private final long demandNanos;

    public BenchmarkPhaseWorkloadPlan(BenchmarkTimeline timeline, BenchmarkParameters ceiling,
                                      List<Profile> profiles) {
        this.timeline = Objects.requireNonNull(timeline, "timeline");
        this.ceiling = Objects.requireNonNull(ceiling, "ceiling");
        this.profiles = List.copyOf(profiles);
        var demandPhases = timeline.phases().stream()
                .filter(phase -> phase.kind() != BenchmarkTimeline.PhaseKind.DRAIN).toList();
        long drains = timeline.phases().stream()
                .filter(phase -> phase.kind() == BenchmarkTimeline.PhaseKind.DRAIN).count();
        if (drains != 1 || timeline.phases().getLast().kind() != BenchmarkTimeline.PhaseKind.DRAIN) {
            throw new IllegalArgumentException("Phase workload plan requires exactly one final DRAIN phase");
        }
        if (this.profiles.size() != demandPhases.size()) {
            throw new IllegalArgumentException("Every demand phase requires exactly one ordered profile");
        }
        var resolved = new ArrayList<LoadWindow>(this.profiles.size());
        long start = 0;
        for (int index = 0; index < this.profiles.size(); index++) {
            var phase = demandPhases.get(index);
            var profile = this.profiles.get(index);
            if (!profile.phase().equals(phase.name())) {
                throw new IllegalArgumentException("Profile order/name must match the timeline demand phases");
            }
            validateCeiling(profile);
            long end = Math.addExact(start, phase.duration().toNanos());
            resolved.add(new LoadWindow(start, end, profile));
            start = end;
        }
        windows = List.copyOf(resolved);
        demandNanos = start;
    }

    public static BenchmarkPhaseWorkloadPlan uniform(BenchmarkTimeline timeline, BenchmarkParameters parameters,
                                                     BenchmarkWorkloadMix workload) {
        var profiles = timeline.phases().stream()
                .filter(phase -> phase.kind() != BenchmarkTimeline.PhaseKind.DRAIN)
                .map(phase -> new Profile(phase.name(), parameters.concurrency(),
                        parameters.offeredPerSecond(), workload)).toList();
        return new BenchmarkPhaseWorkloadPlan(timeline, parameters, profiles);
    }

    public List<Profile> profiles() { return profiles; }
    public BenchmarkTimeline timeline() { return timeline; }
    public BenchmarkParameters ceiling() { return ceiling; }
    List<LoadWindow> windows() { return windows; }
    long demandNanos() { return demandNanos; }

    public Profile profileAt(long elapsedNanos) {
        if (elapsedNanos < 0 || elapsedNanos >= demandNanos) {
            throw new IllegalArgumentException("Elapsed time is outside demand phases");
        }
        for (var window : windows) if (elapsedNanos < window.endNanos()) return window.profile();
        throw new IllegalStateException("Demand phase was not resolved");
    }

    /** True when the actual half-open interval contains a planned load/workload transition. */
    public boolean crossesTransition(long startNanos, long endNanos) {
        if (startNanos < 0 || endNanos <= startNanos) {
            throw new IllegalArgumentException("Interval boundaries must be non-negative and ordered");
        }
        for (var window : windows) {
            if (window.endNanos() > startNanos && window.endNanos() < endNanos) return true;
        }
        return false;
    }

    private void validateCeiling(Profile profile) {
        if (profile.concurrency() > ceiling.concurrency()) {
            throw new IllegalArgumentException("Phase concurrency exceeds the run ceiling");
        }
        if (ceiling.loadModel() == BenchmarkParameters.LoadModel.CLOSED_LOOP) {
            if (profile.offeredPerSecond() != 0) {
                throw new IllegalArgumentException("Closed-loop phase rate must be zero");
            }
        } else if (profile.offeredPerSecond() <= 0
                || profile.offeredPerSecond() > ceiling.offeredPerSecond()) {
            throw new IllegalArgumentException("Rate-controlled phase rate must be positive and within the run ceiling");
        }
    }
}
