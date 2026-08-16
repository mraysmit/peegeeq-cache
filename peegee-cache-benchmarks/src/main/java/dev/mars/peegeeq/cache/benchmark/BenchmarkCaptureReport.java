package dev.mars.peegeeq.cache.benchmark;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Aggregate status for a repeatable benchmark capture. */
public record BenchmarkCaptureReport(String benchmarkId, String status, Instant startedAtUtc,
                                     Instant completedAtUtc, int runsRequested,
                                     List<CapturedBenchmarkRun> runs) {

    public BenchmarkCaptureReport {
        Objects.requireNonNull(benchmarkId, "benchmarkId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(startedAtUtc, "startedAtUtc");
        Objects.requireNonNull(completedAtUtc, "completedAtUtc");
        runs = List.copyOf(runs);
        if (benchmarkId.isBlank() || runsRequested <= 0 || runs.size() > runsRequested) {
            throw new IllegalArgumentException("Invalid benchmark capture report");
        }
    }

    long successfulRuns() {
        return runs.stream().filter(run -> "passed".equals(run.status())).count();
    }
}
