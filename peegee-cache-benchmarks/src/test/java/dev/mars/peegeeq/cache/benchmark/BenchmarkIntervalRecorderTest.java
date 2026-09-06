package dev.mars.peegeeq.cache.benchmark;

import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class BenchmarkIntervalRecorderTest {
    @TempDir Path directory;

    @Test
    void snapshotsCountersAndOutcomeDistributionsTogetherAndResetsOnlyIntervalSamples() {
        var clock = new AtomicLong();
        var recorder = new BenchmarkIntervalRecorder(List.of(0L, 10L, 100L), clock::get);
        clock.set(1);
        request(recorder);
        recorder.complete(BenchmarkIntervalRecorder.Outcome.SUCCESS, 10, 20);
        request(recorder);
        recorder.complete(BenchmarkIntervalRecorder.Outcome.TIMEOUT, 101, 120);
        clock.set(2);
        var first = recorder.checkpoint();
        assertEquals(2, first.interval().completed());
        assertEquals(List.of(0L, 1L, 0L), first.distributions().get("successfulService").counts());
        assertEquals(1, first.distributions().get("timedOutService").overflowCount());
        assertEquals(2, first.interval().endNanos());
        // An event exactly at the last boundary belongs to the next half-open interval.
        request(recorder);
        clock.set(3);
        var second = recorder.checkpoint();
        assertEquals(2, second.interval().startNanos());
        assertEquals(0, second.interval().completed());
        assertEquals(1, second.interval().after().inFlight());
        assertEquals(0, second.distributions().get("successfulService").sampleCount());
        recorder.complete(BenchmarkIntervalRecorder.Outcome.FAILURE, 5, 9);
        clock.set(4);
        var third = recorder.checkpoint();
        assertEquals(0, third.interval().started());
        assertEquals(1, third.interval().failed());
        assertEquals(1, first.distributions().get("successfulService").sampleCount());
    }

    @Test
    void invalidEventsAndPrematureCheckpointsDoNotLoseEvidence() {
        var clock = new AtomicLong();
        var recorder = new BenchmarkIntervalRecorder(List.of(10L), clock::get);
        clock.set(10);
        assertThrows(IllegalArgumentException.class, recorder::admit);
        request(recorder);
        assertThrows(IllegalArgumentException.class, () -> recorder.complete(BenchmarkIntervalRecorder.Outcome.SUCCESS, 3, 2));
        recorder.complete(BenchmarkIntervalRecorder.Outcome.SUCCESS, 2, 3);
        assertThrows(IllegalArgumentException.class, recorder::checkpoint);
        clock.set(9);
        assertThrows(IllegalArgumentException.class, recorder::schedule);
        clock.set(11);
        var sample = recorder.checkpoint();
        assertEquals(1, sample.interval().completed());
        assertEquals(1, sample.distributions().get("successfulService").sampleCount());
        assertThrows(IllegalArgumentException.class, () -> recorder.complete(BenchmarkIntervalRecorder.Outcome.SUCCESS, 2, 3));
    }

    @Test
    void preservesRejectionsAndQueueExpiryWithoutPollutingSuccessfulLatency() {
        var clock = new AtomicLong();
        var recorder = new BenchmarkIntervalRecorder(List.of(10L), clock::get);
        recorder.schedule(); recorder.reject();
        recorder.schedule(); recorder.admit(); recorder.expireBeforeStart();
        recorder.schedule();
        clock.set(1);
        var sample = recorder.checkpoint();
        assertEquals(1, sample.interval().rejected());
        assertEquals(1, sample.interval().expiredBeforeStart());
        assertEquals(1, sample.interval().after().pendingAdmission());
        assertEquals(0, sample.distributions().get("successfulService").sampleCount());
    }

    @Test
    void concurrentRecordingAndBoundaryRolloverConserveAllSamples() throws Exception {
        var clock = new AtomicLong(0);
        var recorder = new BenchmarkIntervalRecorder(List.of(10L, 100L), clock::get);
        var barrier = new CyclicBarrier(5);
        try (var executor = Executors.newFixedThreadPool(4)) {
            var tasks = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int worker = 0; worker < 4; worker++) {
                tasks.add(executor.submit(() -> {
                    for (int phase = 0; phase < 2; phase++) {
                        for (int i = 0; i < 1_000; i++) {
                            request(recorder);
                            recorder.complete(BenchmarkIntervalRecorder.Outcome.SUCCESS, 10, 20);
                        }
                        barrier.await(10, TimeUnit.SECONDS);
                        barrier.await(10, TimeUnit.SECONDS);
                    }
                    return null;
                }));
            }
            for (int phase = 0; phase < 2; phase++) {
                barrier.await(10, TimeUnit.SECONDS);
                clock.incrementAndGet();
                var sample = recorder.checkpoint();
                assertEquals(4_000, sample.interval().completed());
                assertEquals(4_000, sample.distributions().get("successfulService").sampleCount());
                barrier.await(10, TimeUnit.SECONDS);
            }
            for (var task : tasks) task.get(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void rejectsDistributionsThatDoNotMatchIntervalOutcomes() {
        var clock = new AtomicLong();
        var recorder = new BenchmarkIntervalRecorder(List.of(10L), clock::get);
        request(recorder);
        recorder.complete(BenchmarkIntervalRecorder.Outcome.SUCCESS, 1, 2);
        clock.set(1);
        var sample = recorder.checkpoint();
        var altered = new java.util.HashMap<>(sample.distributions());
        altered.put("successfulService", new BenchmarkLatencyDistribution(List.of(10L), List.of(2L), 0));
        assertThrows(IllegalArgumentException.class, () -> new BenchmarkRunEvidence.Measurement(
                "cache", "workflow", "observe", sample.interval(), Map.of(), altered));
        assertThrows(IllegalArgumentException.class, () -> new BenchmarkRunEvidence.Measurement(
                "cache", "workflow", "observe", sample.interval(), Map.of(),
                Map.of("unknown", new BenchmarkLatencyDistribution(List.of(10L), List.of(1L), 0))));
    }

    @Test
    void publishesCollectedDistributionsInsideTheSameRunJson() throws Exception {
        var clock = new AtomicLong();
        var recorder = new BenchmarkIntervalRecorder(List.of(10L, 100L), clock::get);
        request(recorder);
        recorder.complete(BenchmarkIntervalRecorder.Outcome.SUCCESS, 8, 12);
        clock.set(1_000_000_000L);
        var sample = recorder.checkpoint();
        var measurement = new BenchmarkRunEvidence.Measurement("cache-set-get", "workflow", "observe",
                sample.interval(), Map.of(), sample.distributions());
        var evidence = BenchmarkRunJsonWriterTest.evidence(BenchmarkRunEvidence.Status.COMPLETED, List.of(measurement), "done");
        var writer = BenchmarkRunJsonWriter.create(directory, evidence);
        var json = new JsonObject(Files.readString(writer.path()));
        var distributions = json.getJsonArray("measurements").getJsonObject(0).getJsonObject("latencyDistributions");
        assertEquals("COLLECTED", distributions.getString("status"));
        assertEquals(1L, distributions.getJsonObject("series").getJsonObject("successfulService").getLong("sampleCount"));
        assertEquals(10L, distributions.getJsonObject("series").getJsonObject("successfulService").getLong("p99UpperBoundNanos"));
        assertNull(distributions.getJsonObject("series").getJsonObject("failedService").getValue("p99UpperBoundNanos"));
    }

    private static void request(BenchmarkIntervalRecorder recorder) {
        recorder.schedule(); recorder.admit(); recorder.start();
    }
}
