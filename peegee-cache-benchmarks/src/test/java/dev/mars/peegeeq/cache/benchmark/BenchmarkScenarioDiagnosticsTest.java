package dev.mars.peegeeq.cache.benchmark;

import io.vertx.core.Promise;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BenchmarkScenarioDiagnosticsTest {

    @Test
    void timedOutOperationIdentifiesItsScenarioAndPreservesTheCause() {
        BenchmarkConfig config = new BenchmarkConfig(
                1, 2, Duration.ofMillis(100), 1,
                Duration.ofMillis(1), Duration.ofSeconds(1), Duration.ofSeconds(1), 100);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> CacheBenchmarkMain.runSustained(
                        "diagnostic-timeout", config, () -> Promise.<Void>promise().future()));

        assertTrue(failure.getMessage().contains("diagnostic-timeout"));
        assertTrue(failure.getMessage().contains("timed out"));
        assertInstanceOf(TimeoutException.class, failure.getCause());
    }
}
