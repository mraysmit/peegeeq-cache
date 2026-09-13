package dev.mars.peegeeq.cache.benchmark;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.WorkerExecutor;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/** Finite synthetic sweep of managed-driver arrival and dispatch fidelity; it measures no product capacity. */
public final class BenchmarkGeneratorCalibration {
    public record Config(List<Double> offeredRates, int concurrency, Duration measurement, Duration drain,
                         Duration samplingInterval, Duration arrivalInterval,
                         int maximumArrivalsPerAdvance) {
        public Config {
            offeredRates = List.copyOf(offeredRates);
            if (offeredRates.isEmpty() || offeredRates.size() > 64) {
                throw new IllegalArgumentException("Generator calibration requires 1 to 64 rates");
            }
            double previous = 0;
            for (double rate : offeredRates) {
                if (!Double.isFinite(rate) || rate <= previous) {
                    throw new IllegalArgumentException("Offered rates must be finite, positive and strictly increasing");
                }
                previous = rate;
            }
            if (concurrency <= 0 || concurrency > 1024 || maximumArrivalsPerAdvance <= 0) {
                throw new IllegalArgumentException("Invalid generator concurrency or arrival cap");
            }
            wholePositiveMillis(measurement, "measurement");
            wholePositiveMillis(drain, "drain");
            wholePositiveMillis(samplingInterval, "sampling interval");
            wholePositiveMillis(arrivalInterval, "arrival interval");
            if (samplingInterval.compareTo(measurement) > 0 || arrivalInterval.compareTo(samplingInterval) > 0) {
                throw new IllegalArgumentException("Calibration cadence must fit within measurement windows");
            }
        }
    }

    public record Observation(double offeredPerSecond, long attemptsStarted, long generatorMissed,
                              long admissionRejected, long maximumScheduleLagNanos,
                              BenchmarkRunEvidence.Status status, Path evidencePath) {
        public Observation {
            if (!Double.isFinite(offeredPerSecond) || offeredPerSecond <= 0 || attemptsStarted < 0
                    || generatorMissed < 0 || admissionRejected < 0 || maximumScheduleLagNanos < 0) {
                throw new IllegalArgumentException("Invalid generator observation");
            }
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(evidencePath, "evidencePath");
        }
    }

    private BenchmarkGeneratorCalibration() { }

    public static Future<List<Observation>> run(Vertx vertx, WorkerExecutor checkpointWorker,
                                                 Path outputDirectory, Config config) {
        try {
            Objects.requireNonNull(vertx, "vertx");
            Objects.requireNonNull(checkpointWorker, "checkpointWorker");
            Objects.requireNonNull(outputDirectory, "outputDirectory");
            Objects.requireNonNull(config, "config");
            var state = new State(vertx, checkpointWorker, outputDirectory, config);
            vertx.runOnContext(ignored -> state.next());
            return state.done.future();
        } catch (RuntimeException invalid) {
            return Future.failedFuture(invalid);
        }
    }

    private static final class State {
        private static final List<Long> LATENCY_BOUNDS =
                List.of(1_000L, 10_000L, 100_000L, 1_000_000L, 10_000_000L);
        final Vertx vertx;
        final WorkerExecutor worker;
        final Path directory;
        final Config config;
        final ArrayList<Observation> observations = new ArrayList<>();
        final Promise<List<Observation>> done = Promise.promise();
        int index;

        State(Vertx vertx, WorkerExecutor worker, Path directory, Config config) {
            this.vertx = vertx;
            this.worker = worker;
            this.directory = directory;
            this.config = config;
        }

        void next() {
            if (index == config.offeredRates().size()) {
                done.tryComplete(List.copyOf(observations));
                return;
            }
            double rate = config.offeredRates().get(index);
            var timeline = new BenchmarkTimeline(List.of(
                    new BenchmarkTimeline.Phase("measure", BenchmarkTimeline.PhaseKind.SUSTAINED,
                            config.measurement()),
                    new BenchmarkTimeline.Phase("drain", BenchmarkTimeline.PhaseKind.DRAIN, config.drain())),
                    config.samplingInterval());
            var parameters = new BenchmarkParameters(BenchmarkParameters.LoadModel.RATE_CONTROLLED,
                    config.concurrency(), config.concurrency(), rate, config.concurrency(), config.drain());
            var plan = new BenchmarkExperiment.Run(BenchmarkExperiment.SCHEMA_VERSION,
                    "generator-calibration", 0, index, 0, 0, parameters, timeline);
            Instant now = Instant.now();
            var evidence = new BenchmarkRunEvidence(UUID.randomUUID(), plan, now, now,
                    BenchmarkRunEvidence.Status.RUNNING, BenchmarkRunEvidence.Validity.UNASSESSED, "",
                    Map.of("purpose", "GENERATOR_CALIBRATION",
                            "scope", "Synthetic immediate completions; no cache or PostgreSQL capacity measurement"),
                    List.of(), List.of());
            var workload = new BenchmarkWorkloadMix(List.of(new BenchmarkWorkloadMix.Weight("generator.noop", 1)));
            var phasePlan = BenchmarkPhaseWorkloadPlan.uniform(timeline, parameters, workload);
            var options = new BenchmarkManagedExecution.Options("generator-calibration", "synthetic-noop",
                    config.maximumArrivalsPerAdvance(), LATENCY_BOUNDS, config.arrivalInterval(),
                    config.arrivalInterval(), new BenchmarkCheckpointWriter.Limits(16, 4_194_304, 1),
                    Duration.ofMillis(10), phasePlan, BenchmarkRetryPolicy.none());
            var attempts = new AtomicLong();
            BenchmarkManagedExecution.start(vertx, worker, directory, evidence, options, attempt -> {
                attempts.incrementAndGet();
                return Future.succeededFuture();
            }).compose(BenchmarkManagedExecution.Execution::completion).onSuccess(result -> {
                var statistics = result.statistics();
                observations.add(new Observation(rate, attempts.get(), statistics.generatorMissed(),
                        statistics.admissionRejected(), statistics.maximumScheduleLagNanos(), result.status(),
                        result.path()));
                index++;
                vertx.runOnContext(ignored -> next());
            }).onFailure(done::tryFail);
        }
    }

    private static void wholePositiveMillis(Duration duration, String field) {
        Objects.requireNonNull(duration, field);
        long nanos;
        try {
            nanos = duration.toNanos();
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException(field + " exceeds nanosecond range", overflow);
        }
        if (nanos < 1_000_000 || nanos % 1_000_000 != 0) {
            throw new IllegalArgumentException(field + " must be positive whole milliseconds");
        }
    }
}
