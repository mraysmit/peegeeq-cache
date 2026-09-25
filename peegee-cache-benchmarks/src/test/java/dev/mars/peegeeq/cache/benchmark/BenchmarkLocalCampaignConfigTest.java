package dev.mars.peegeeq.cache.benchmark;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BenchmarkLocalCampaignConfigTest {
    @Test
    void parsesFiniteLocalCampaignControls() {
        var values = new Properties();
        values.setProperty("campaign.outputDirectory", "benchmark-results/test-run");
        values.setProperty("campaign.repetitions", "2");
        values.setProperty("campaign.rates", "25, 50.5");
        values.setProperty("campaign.phaseMillis", "200");
        values.setProperty("campaign.sampleMillis", "50");
        values.setProperty("campaign.datasetCardinality", "40");
        values.setProperty("campaign.payloadBytes", "128");

        var config = BenchmarkLocalCampaignConfig.fromProperties(values);
        assertEquals(Path.of("benchmark-results/test-run"), config.outputDirectory());
        assertEquals(List.of(25.0, 50.5), config.offeredRates());
        assertEquals(Duration.ofMillis(200), config.phaseDuration());
        assertEquals(2, config.repetitions());
        Path repositoryRoot = Path.of("repo").toAbsolutePath().normalize();
        assertEquals(repositoryRoot.resolve("benchmark-results/test-run"),
                BenchmarkLocalCampaignMain.resolveOutputDirectory(config, repositoryRoot));
    }

    @Test
    void rejectsUnboundedOrEmptyCampaigns() {
        var values = new Properties();
        values.setProperty("campaign.rates", "");
        assertThrows(IllegalArgumentException.class, () -> BenchmarkLocalCampaignConfig.fromProperties(values));
    }
}
