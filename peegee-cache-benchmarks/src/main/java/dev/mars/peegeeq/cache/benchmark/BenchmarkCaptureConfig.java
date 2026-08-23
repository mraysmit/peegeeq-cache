package dev.mars.peegeeq.cache.benchmark;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Properties;

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
        return fromProperties(System.getProperties());
    }

    static BenchmarkCaptureConfig fromProperties(Properties properties) {
        Objects.requireNonNull(properties, "properties");
        return new BenchmarkCaptureConfig(
                integer(properties, "peegeeq.benchmark.capture.runs", 3),
                Path.of(properties.getProperty(
                        "peegeeq.benchmark.capture.outputRoot", "benchmark-results")),
                Path.of(properties.getProperty(
                        "peegeeq.benchmark.capture.repositoryRoot", "."))
                        .toAbsolutePath().normalize(),
                properties.getProperty("peegeeq.benchmark.capture.topology",
                        "Local benchmark JVM and Testcontainers PostgreSQL on the same Docker engine"),
                properties.getProperty("peegeeq.test.postgres.image", "postgres:18.3-alpine"),
                Boolean.parseBoolean(properties.getProperty(
                        "peegeeq.benchmark.capture.requireCleanGit", "false")),
                Boolean.parseBoolean(properties.getProperty(
                        "peegeeq.benchmark.capture.stopOnFailure", "false")));
    }

    private static int integer(Properties properties, String name, int defaultValue) {
        String value = properties.getProperty(name);
        if (value == null) return defaultValue;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException(name + " must be an integer", failure);
        }
    }

    Path resolvedOutputRoot() {
        return outputRoot.isAbsolute() ? outputRoot.normalize() : repositoryRoot.resolve(outputRoot).normalize();
    }
}
