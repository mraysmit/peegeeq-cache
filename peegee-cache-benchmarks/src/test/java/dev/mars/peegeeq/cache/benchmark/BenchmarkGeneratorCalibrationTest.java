package dev.mars.peegeeq.cache.benchmark;

import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(VertxExtension.class)
@io.vertx.junit5.Timeout(value = 15, timeUnit = TimeUnit.SECONDS)
class BenchmarkGeneratorCalibrationTest {
    @TempDir Path directory;

    @Test
    void calibrationSweepIsFiniteOrderedAndMillisecondBased() {
        var config = config(List.of(100.0, 250.0));
        assertEquals(2, config.offeredRates().size());
        assertThrows(IllegalArgumentException.class, () -> config(List.of()));
        assertThrows(IllegalArgumentException.class, () -> config(List.of(100.0, 100.0)));
        assertThrows(IllegalArgumentException.class, () -> config(List.of(Double.NaN)));
        assertThrows(IllegalArgumentException.class, () -> new BenchmarkGeneratorCalibration.Config(
                List.of(100.0), 2, Duration.ofMillis(20), Duration.ofMillis(100),
                Duration.ofMillis(5), Duration.ofNanos(1), 8));
    }

    @Test
    void managedNoopSweepProducesOneAuditableObservationPerRate(Vertx vertx, VertxTestContext ctx) {
        var worker = vertx.createSharedWorkerExecutor("generator-calibration-test", 1);
        BenchmarkGeneratorCalibration.run(vertx, worker, directory, config(List.of(50.0, 100.0)))
                .compose(observations -> vertx.executeBlocking(() -> {
                    assertEquals(List.of(50.0, 100.0), observations.stream()
                            .map(BenchmarkGeneratorCalibration.Observation::offeredPerSecond).toList());
                    for (var observation : observations) {
                        assertEquals(BenchmarkRunEvidence.Status.COMPLETED, observation.status());
                        assertTrue(observation.attemptsStarted() > 0);
                        assertTrue(observation.maximumScheduleLagNanos() >= 0);
                        assertTrue(Files.isRegularFile(observation.evidencePath()));
                        var json = new io.vertx.core.json.JsonObject(Files.readString(observation.evidencePath()));
                        assertEquals("GENERATOR_CALIBRATION", json.getJsonObject("environment").getString("purpose"));
                        var measurements = json.getJsonArray("measurements");
                        var metrics = measurements.getJsonObject(measurements.size() - 1).getJsonObject("metrics");
                        assertTrue(metrics.containsKey("generator.selectionDispatchNanosTotal"));
                        assertTrue(metrics.containsKey("generator.selectionDispatchNanosMaximum"));
                    }
                    return null;
                })).eventually(worker::close).onComplete(ctx.succeeding(ignored -> ctx.completeNow()));
    }

    private static BenchmarkGeneratorCalibration.Config config(List<Double> rates) {
        return new BenchmarkGeneratorCalibration.Config(rates, 2, Duration.ofMillis(20),
                Duration.ofMillis(100), Duration.ofMillis(5), Duration.ofMillis(1), 8);
    }
}
