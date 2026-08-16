package dev.mars.peegeeq.cache.benchmark;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BenchmarkRuntimeLayoutTest {

    @Test
    void sustainedWorkloadManagersDoNotRunExpirySweepers() {
        var runtime = CacheBenchmarkMain.sustainedWorkloadRuntime();

        assertFalse(runtime.enableExpirySweeper());
    }

    @Test
    void foregroundPoolUsesTheValidatedHeadroomConfiguration() {
        BenchmarkConfig config = BenchmarkConfig.fromSystemProperties();

        assertEquals(config.poolSize(), CacheBenchmarkMain.foregroundPoolOptions(config).getMaxSize());
    }

    @Test
    void dedicatedExpiryManagerRunsTheMeasuredSweeper() {
        var runtime = CacheBenchmarkMain.expiryMeasurementRuntime();

        assertTrue(runtime.enableExpirySweeper());
        assertEquals(Duration.ofMillis(50), runtime.expirySweepInterval());
        assertEquals(250, runtime.expirySweepBatchSize());
    }
}
