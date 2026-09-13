package dev.mars.peegeeq.cache.benchmark;

import java.util.Objects;
import java.util.Properties;

/** Explicit, bounded controls for one cursor-based cache scan traversal. */
public record BenchmarkScanParameters(int pageSize, boolean includeValues,
                                      boolean includeExpired, String prefix) {
    public BenchmarkScanParameters {
        if (pageSize < 1 || pageSize > 10_000) {
            throw new IllegalArgumentException("Scan page size must be between 1 and 10000");
        }
        if (prefix != null && prefix.isBlank()) {
            throw new IllegalArgumentException("Scan prefix must be non-blank when present");
        }
    }

    public static BenchmarkScanParameters fromProperties(Properties properties) {
        Objects.requireNonNull(properties, "properties");
        String rawPrefix = properties.getProperty("scenario.scan.prefix");
        return new BenchmarkScanParameters(
                integer(properties, "scenario.scan.pageSize", 100),
                bool(properties, "scenario.scan.includeValues", true),
                bool(properties, "scenario.scan.includeExpired", false),
                rawPrefix == null ? null : rawPrefix);
    }

    private static int integer(Properties properties, String name, int fallback) {
        String value = properties.getProperty(name);
        try {
            return value == null ? fallback : Integer.parseInt(value);
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException(name + " must be an integer", invalid);
        }
    }

    private static boolean bool(Properties properties, String name, boolean fallback) {
        String value = properties.getProperty(name);
        if (value == null) return fallback;
        if ("true".equalsIgnoreCase(value)) return true;
        if ("false".equalsIgnoreCase(value)) return false;
        throw new IllegalArgumentException(name + " must be true or false");
    }
}
