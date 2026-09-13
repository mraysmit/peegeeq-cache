package dev.mars.peegeeq.cache.benchmark;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.WorkerExecutor;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Sequential, finite bridge from resolved experiment runs to managed execution. */
public final class BenchmarkCampaignExecutor {
    @FunctionalInterface
    public interface Preparer {
        /** Performs the caller-owned reset/provisioning boundary and supplies one runnable definition. */
        Future<PreparedRun> prepare(BenchmarkExperiment.Run run);
    }

    public record PreparedRun(BenchmarkRunEvidence initial, BenchmarkManagedExecution.Options options,
                              BenchmarkManagedExecution.Operation operation) {
        public PreparedRun {
            Objects.requireNonNull(initial, "initial");
            Objects.requireNonNull(options, "options");
            Objects.requireNonNull(operation, "operation");
            if (initial.status() != BenchmarkRunEvidence.Status.RUNNING
                    || !initial.measurements().isEmpty() || !initial.diagnostics().isEmpty()) {
                throw new IllegalArgumentException("Prepared campaign run must be an empty RUNNING manifest");
            }
        }
    }

    public record RunResult(BenchmarkExperiment.Run run, BenchmarkManagedExecution.Result result) {
        public RunResult {
            Objects.requireNonNull(run, "run");
            Objects.requireNonNull(result, "result");
        }
    }

    private BenchmarkCampaignExecutor() { }

    public static Future<List<RunResult>> run(Vertx vertx, WorkerExecutor checkpointWorker,
                                               Path outputDirectory, BenchmarkExperiment experiment,
                                               long maximumRuns, Preparer preparer) {
        try {
            Objects.requireNonNull(vertx, "vertx");
            Objects.requireNonNull(checkpointWorker, "checkpointWorker");
            Objects.requireNonNull(outputDirectory, "outputDirectory");
            Objects.requireNonNull(experiment, "experiment");
            Objects.requireNonNull(preparer, "preparer");
            if (maximumRuns <= 0 || experiment.runCount() > maximumRuns) {
                throw new IllegalArgumentException("Campaign exceeds execution budget: " + experiment.runCount());
            }
            var state = new State(vertx, checkpointWorker, outputDirectory, experiment, preparer);
            vertx.runOnContext(ignored -> state.next());
            return state.done.future();
        } catch (RuntimeException invalid) {
            return Future.failedFuture(invalid);
        }
    }

    private static final class State {
        final Vertx vertx;
        final WorkerExecutor worker;
        final Path directory;
        final BenchmarkExperiment experiment;
        final Preparer preparer;
        final ArrayList<RunResult> results = new ArrayList<>();
        final Promise<List<RunResult>> done = Promise.promise();
        long index;

        State(Vertx vertx, WorkerExecutor worker, Path directory, BenchmarkExperiment experiment,
              Preparer preparer) {
            this.vertx = vertx;
            this.worker = worker;
            this.directory = directory;
            this.experiment = experiment;
            this.preparer = preparer;
        }

        void next() {
            if (index == experiment.runCount()) {
                done.tryComplete(List.copyOf(results));
                return;
            }
            var resolved = experiment.runAt(index);
            Future<PreparedRun> preparation;
            try {
                preparation = Objects.requireNonNull(preparer.prepare(resolved),
                        "Campaign preparer returned null future");
            } catch (Throwable failure) {
                done.tryFail(failure);
                return;
            }
            preparation.compose(prepared -> {
                if (!resolved.equals(prepared.initial().plan())) {
                    return Future.failedFuture("Prepared manifest does not match resolved campaign run");
                }
                return BenchmarkManagedExecution.start(vertx, worker, directory, prepared.initial(),
                                prepared.options(), prepared.operation())
                        .compose(BenchmarkManagedExecution.Execution::completion);
            }).onSuccess(result -> {
                results.add(new RunResult(resolved, result));
                index++;
                vertx.runOnContext(ignored -> next());
            }).onFailure(done::tryFail);
        }
    }
}
