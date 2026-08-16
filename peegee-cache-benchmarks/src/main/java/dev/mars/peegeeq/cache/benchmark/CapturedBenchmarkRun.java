package dev.mars.peegeeq.cache.benchmark;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/** One successful or failed repetition embedded in the HTML evidence report. */
public record CapturedBenchmarkRun(int run, String status, Instant startedAtUtc,
                                   Instant completedAtUtc, BenchmarkRunResult result,
                                   String failure, String log) {

    public CapturedBenchmarkRun {
        if (run <= 0) {
            throw new IllegalArgumentException("run must be positive");
        }
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(startedAtUtc, "startedAtUtc");
        Objects.requireNonNull(completedAtUtc, "completedAtUtc");
        Objects.requireNonNull(log, "log");
        if (completedAtUtc.isBefore(startedAtUtc)) {
            throw new IllegalArgumentException("completedAtUtc must not precede startedAtUtc");
        }
        if ((result == null) == (failure == null)) {
            throw new IllegalArgumentException("A captured run must have either a result or a failure");
        }
    }

    static CapturedBenchmarkRun passed(int run, Instant startedAtUtc, Instant completedAtUtc,
                                       BenchmarkRunResult result, String log) {
        return new CapturedBenchmarkRun(run, "passed", startedAtUtc, completedAtUtc,
                Objects.requireNonNull(result, "result"), null, log);
    }

    static CapturedBenchmarkRun failed(int run, Instant startedAtUtc, Instant completedAtUtc,
                                       Throwable failure, String log) {
        return new CapturedBenchmarkRun(run, "failed", startedAtUtc, completedAtUtc,
                null, failureSummary(failure), log);
    }

    double elapsedSeconds() {
        return Duration.between(startedAtUtc, completedAtUtc).toNanos() / 1_000_000_000.0;
    }

    private static String failureSummary(Throwable failure) {
        Objects.requireNonNull(failure, "failure");
        String message = failure.getMessage();
        return failure.getClass().getName() + (message == null ? "" : ": " + message);
    }
}
