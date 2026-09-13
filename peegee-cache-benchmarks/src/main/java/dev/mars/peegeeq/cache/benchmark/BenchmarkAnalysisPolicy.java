package dev.mars.peegeeq.cache.benchmark;

import java.time.Duration;
import java.util.Objects;

/** Versioned detector settings. These are characterisation rules, not production SLOs. */
public record BenchmarkAnalysisPolicy(int version, String referencePhaseKind, long minimumSamples,
                                      double latencyRatio, int persistenceIntervals,
                                      double recoveryRatio, Duration analysisWindow) {
    public BenchmarkAnalysisPolicy {
        if (version != 1) throw new IllegalArgumentException("Unsupported analysis policy version");
        Objects.requireNonNull(referencePhaseKind, "referencePhaseKind");
        Objects.requireNonNull(analysisWindow, "analysisWindow");
        if (referencePhaseKind.isBlank() || minimumSamples <= 0 || !Double.isFinite(latencyRatio)
                || latencyRatio <= 1 || persistenceIntervals <= 0 || !Double.isFinite(recoveryRatio)
                || recoveryRatio <= 0 || analysisWindow.isZero() || analysisWindow.isNegative()) {
            throw new IllegalArgumentException("Analysis policy values are invalid");
        }
    }

    /** Version-1 reliability trigger; part of the policy version rather than a production SLO. */
    public double maximumAdverseOutcomeFraction() { return 0.05; }

    /** Version-1 pressure trigger requires this many queued/outstanding items of positive growth. */
    public double minimumPressureGrowth() { return 1.0; }
}
