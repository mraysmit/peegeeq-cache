package dev.mars.peegeeq.cache.benchmark;

import java.nio.file.Path;
import java.util.Objects;

/** Cross-platform settings for repeated Java benchmark capture. */
public record BenchmarkCaptureConfig(int runs, Path outputRoot, Path repositoryRoot,
                                     String topology, String postgresImage,
                                     boolean requireCleanGit, boolean stopOnFailure) {

    public BenchmarkCaptureConfig {
        Objects.requireNonNull(outputRoot, "outputRoot");
        Objects.requireNonNull(repositoryRoot, "repositoryRoot");
        Objects.requireNonNull(topology, "topology");
        Objects.requireNonNull(postgresImage, "postgresImage");
        if (runs <= 0 || topology.isBlank() || postgresImage.isBlank()) {
            throw new IllegalArgumentException("Capture runs and text settings must be positive/non-blank");
        }
    }

    public static BenchmarkCaptureConfig fromSystemProperties() {
        return new BenchmarkCaptureConfig(
                Integer.getInteger("peegeeq.benchmark.capture.runs", 3),
                Path.of(System.getProperty("peegeeq.benchmark.capture.outputRoot", "benchmark-results")),
                Path.of(System.getProperty("peegeeq.benchmark.capture.repositoryRoot", "."))
                        .toAbsolutePath().normalize(),
                System.getProperty("peegeeq.benchmark.capture.topology",
                        "Local benchmark JVM and Testcontainers PostgreSQL on the same Docker engine"),
                System.getProperty("peegeeq.test.postgres.image", "postgres:18.3-alpine"),
                Boolean.getBoolean("peegeeq.benchmark.capture.requireCleanGit"),
                Boolean.getBoolean("peegeeq.benchmark.capture.stopOnFailure"));
    }

    Path resolvedOutputRoot() {
        return outputRoot.isAbsolute() ? outputRoot.normalize() : repositoryRoot.resolve(outputRoot).normalize();
    }
}
