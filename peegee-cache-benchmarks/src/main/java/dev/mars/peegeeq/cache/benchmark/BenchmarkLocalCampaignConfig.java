package dev.mars.peegeeq.cache.benchmark;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Properties;

/** Bounded command-line configuration for the disposable local characterisation campaign. */
public record BenchmarkLocalCampaignConfig(Path outputDirectory, int repetitions,
                                           List<Double> offeredRates, int concurrency, int poolSize,
                                           Duration phaseDuration, Duration sampleInterval,
                                           int datasetCardinality, int payloadBytes) {
    public BenchmarkLocalCampaignConfig {
        Objects.requireNonNull(outputDirectory, "outputDirectory");
        offeredRates = List.copyOf(offeredRates);
        Objects.requireNonNull(phaseDuration, "phaseDuration");
        Objects.requireNonNull(sampleInterval, "sampleInterval");
        if (repetitions < 1 || repetitions > 100 || offeredRates.isEmpty()
                || concurrency < 1 || concurrency > 10_000 || poolSize < 1 || poolSize > 1_000
                || datasetCardinality < 1 || payloadBytes < 1
                || phaseDuration.isZero() || phaseDuration.isNegative()
                || sampleInterval.isZero() || sampleInterval.isNegative()
                || sampleInterval.compareTo(phaseDuration) > 0) {
            throw new IllegalArgumentException("Local campaign controls are outside their finite bounds");
        }
        offeredRates.forEach(rate -> BenchmarkParameters.validateRate(BenchmarkParameters.LoadModel.RATE_CONTROLLED, rate));
        // Reuse the scenario's hard safety ceilings.
        BenchmarkScenarioParameters.uniform(datasetCardinality, payloadBytes, Duration.ZERO,
                BenchmarkScenarioParameters.TelemetryMode.OFF);
    }

    public static BenchmarkLocalCampaignConfig fromProperties(Properties properties) {
        Objects.requireNonNull(properties, "properties");
        String rates = properties.getProperty("campaign.rates", "50,100");
        if (rates.isBlank()) throw new IllegalArgumentException("campaign.rates must not be empty");
        try {
            List<Double> parsed = Arrays.stream(rates.split(",", -1)).map(String::trim)
                    .map(value -> {
                        if (value.isEmpty()) throw new IllegalArgumentException("campaign.rates contains an empty value");
                        return Double.parseDouble(value);
                    }).toList();
            return new BenchmarkLocalCampaignConfig(
                    Path.of(properties.getProperty("campaign.outputDirectory", "benchmark-results/characterisation-local")),
                    integer(properties, "campaign.repetitions", 3), parsed,
                    integer(properties, "campaign.concurrency", 4), integer(properties, "campaign.poolSize", 4),
                    Duration.ofMillis(longValue(properties, "campaign.phaseMillis", 1_000)),
                    Duration.ofMillis(longValue(properties, "campaign.sampleMillis", 100)),
                    integer(properties, "campaign.datasetCardinality", 100),
                    integer(properties, "campaign.payloadBytes", 256));
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("Local campaign numeric controls are invalid", invalid);
        }
    }

    private static int integer(Properties properties, String name, int fallback) {
        return Math.toIntExact(longValue(properties, name, fallback));
    }

    private static long longValue(Properties properties, String name, long fallback) {
        String value = properties.getProperty(name);
        return value == null ? fallback : Long.parseLong(value);
    }
}
