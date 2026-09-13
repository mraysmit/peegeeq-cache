package dev.mars.peegeeq.cache.benchmark;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.WorkerExecutor;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Finite lifecycle orchestrator with target verification, reset, cleanup, analysis and live manifest. */
public final class BenchmarkCampaignRunner {
    public record TargetIdentity(String targetId, String serverVersion, String endpoint, boolean tls) {
        public TargetIdentity {
            named(targetId, "targetId"); named(serverVersion, "serverVersion"); named(endpoint, "endpoint");
        }
    }

    public interface TargetAdapter {
        Future<TargetIdentity> verify(BenchmarkDeploymentTarget target);
        Future<Void> reset(BenchmarkCampaignPlan.Manifest manifest);
        Future<BenchmarkCampaignExecutor.PreparedRun> prepare(BenchmarkCampaignPlan.Manifest manifest);
        Future<Void> cleanup(BenchmarkCampaignPlan.Manifest manifest);
    }

    public record CompletedRun(BenchmarkCampaignPlan.Manifest manifest, Path jsonPath, Path htmlPath,
                               BenchmarkManagedExecution.Result execution) { }
    public record Result(Path manifestPath, TargetIdentity target, List<CompletedRun> runs) {
        public Result { runs = List.copyOf(runs); }
    }

    private BenchmarkCampaignRunner() { }

    public static Future<Result> run(Vertx vertx, WorkerExecutor worker, Path outputDirectory,
                                     BenchmarkCampaignPlan plan, TargetAdapter adapter,
                                     BenchmarkAnalysisPolicy analysisPolicy) {
        try {
            Objects.requireNonNull(vertx, "vertx"); Objects.requireNonNull(worker, "worker");
            Objects.requireNonNull(outputDirectory, "outputDirectory"); Objects.requireNonNull(plan, "plan");
            Objects.requireNonNull(adapter, "adapter"); Objects.requireNonNull(analysisPolicy, "analysisPolicy");
            var state = new State(vertx, worker, outputDirectory.toAbsolutePath().normalize(), plan,
                    plan.resolve(), adapter, analysisPolicy);
            adapter.verify(plan.target()).compose(identity -> state.verified(identity)).onFailure(state.done::tryFail);
            return state.done.future();
        } catch (Throwable failure) { return Future.failedFuture(failure); }
    }

    private static final class State {
        final Vertx vertx; final WorkerExecutor worker; final Path directory; final BenchmarkCampaignPlan plan;
        final List<BenchmarkCampaignPlan.Manifest> manifests; final TargetAdapter adapter;
        final BenchmarkAnalysisPolicy policy; final Promise<Result> done = Promise.promise();
        final ArrayList<CompletedRun> completed = new ArrayList<>(); final ArrayList<String> statuses = new ArrayList<>();
        final Path manifestPath; TargetIdentity identity; int index;

        State(Vertx vertx, WorkerExecutor worker, Path directory, BenchmarkCampaignPlan plan,
              List<BenchmarkCampaignPlan.Manifest> manifests, TargetAdapter adapter, BenchmarkAnalysisPolicy policy) {
            this.vertx = vertx; this.worker = worker; this.directory = directory; this.plan = plan;
            this.manifests = manifests; this.adapter = adapter; this.policy = policy;
            this.manifestPath = directory.resolve("campaign-manifest.json");
            for (int ignored = 0; ignored < manifests.size(); ignored++) statuses.add("PENDING");
        }

        Future<Void> verified(TargetIdentity verified) {
            try {
                if (!verified.targetId().equals(plan.target().id())) throw new IllegalArgumentException("Verified target identity mismatch");
                if (plan.target().tlsRequired() && !verified.tls()) throw new IllegalArgumentException("Target did not verify required TLS");
                identity = verified;
            } catch (Throwable failure) { return Future.failedFuture(failure); }
            return publish().onSuccess(ignored -> next());
        }

        void next() {
            if (done.future().isComplete()) return;
            if (index == manifests.size()) {
                publish().onSuccess(ignored -> done.tryComplete(new Result(manifestPath, identity, completed)))
                        .onFailure(done::tryFail);
                return;
            }
            var manifest = manifests.get(index);
            final boolean[] resetCompleted = {false};
            final boolean[] cleanupAttempted = {false};
            statuses.set(index, "RESETTING");
            publish().compose(ignored -> adapter.reset(manifest))
                    .onSuccess(ignored -> resetCompleted[0] = true)
                    .compose(ignored -> { statuses.set(index, "RUNNING"); return publish(); })
                    .compose(ignored -> adapter.prepare(manifest))
                    .compose(prepared -> {
                        if (!prepared.initial().plan().equals(manifest.run())) {
                            return Future.failedFuture("Prepared run does not match campaign manifest");
                        }
                        return BenchmarkManagedExecution.start(vertx, worker, directory, prepared.initial(),
                                prepared.options(), prepared.operation()).compose(BenchmarkManagedExecution.Execution::completion);
                    }).compose(execution -> {
                        cleanupAttempted[0] = true;
                        return adapter.cleanup(manifest).map(execution);
                    })
                    .compose(execution -> analyse(manifest, execution))
                    .onSuccess(run -> {
                        completed.add(run); statuses.set(index, "COMPLETED"); index++;
                        publish().onSuccess(ignored -> next()).onFailure(done::tryFail);
                    }).onFailure(failure -> {
                        Future<Void> cleanup = resetCompleted[0] && !cleanupAttempted[0]
                                ? safeCleanup(manifest) : Future.succeededFuture();
                        cleanup.onComplete(cleaned -> {
                            if (cleaned.failed() && cleaned.cause() != failure) failure.addSuppressed(cleaned.cause());
                            statuses.set(index, "FAILED: " + failure.getClass().getSimpleName() + ": " + failure.getMessage());
                            publish().onComplete(ignored -> done.tryFail(failure));
                        });
                    });
        }

        Future<Void> safeCleanup(BenchmarkCampaignPlan.Manifest manifest) {
            try { return Objects.requireNonNull(adapter.cleanup(manifest), "Target cleanup returned null future"); }
            catch (Throwable failure) { return Future.failedFuture(failure); }
        }

        Future<CompletedRun> analyse(BenchmarkCampaignPlan.Manifest manifest,
                                     BenchmarkManagedExecution.Result execution) {
            Path html = sibling(execution.path(), ".html");
            return worker.executeBlocking(() -> {
                BenchmarkAnalysisPublisher.publish(execution.path(), html, policy);
                return new CompletedRun(manifest, execution.path(), html, execution);
            });
        }

        Future<Void> publish() {
            return worker.executeBlocking(() -> {
                Files.createDirectories(directory);
                var runs = new JsonArray();
                for (int i = 0; i < manifests.size(); i++) {
                    var manifest = manifests.get(i);
                    runs.add(new JsonObject().put("executionOrder", manifest.executionOrder())
                            .put("configurationIndex", manifest.run().configurationIndex())
                            .put("forkIndex", manifest.run().forkIndex()).put("repetitionIndex", manifest.run().repetitionIndex())
                            .put("resourceName", manifest.resourceName()).put("reset", manifest.reset().name())
                            .put("status", statuses.get(i)));
                    if (i < completed.size()) {
                        CompletedRun run = completed.get(i);
                        runs.getJsonObject(i).put("evidenceJson", run.jsonPath().getFileName().toString())
                                .put("reportHtml", run.htmlPath().getFileName().toString());
                    }
                }
                var json = new JsonObject().put("schemaVersion", 1).put("updatedAtUtc", Instant.now().toString())
                        .put("target", identity == null ? null : new JsonObject().put("id", identity.targetId())
                                .put("serverVersion", identity.serverVersion()).put("endpoint", identity.endpoint())
                                .put("tls", identity.tls()).put("serverOwned", plan.target().serverOwned()))
                        .put("order", plan.order().name()).put("orderSeed", plan.orderSeed())
                        .put("plannedRuns", manifests.size()).put("completedRuns", completed.size()).put("runs", runs);
                replace(manifestPath, (json.encodePrettily() + System.lineSeparator()).getBytes(StandardCharsets.UTF_8));
                return null;
            });
        }
    }

    private static Path sibling(Path json, String suffix) {
        String name = json.getFileName().toString();
        if (name.endsWith(".json")) name = name.substring(0, name.length() - 5);
        return json.resolveSibling(name + suffix);
    }

    private static void replace(Path target, byte[] bytes) throws java.io.IOException {
        Path temporary = Files.createTempFile(target.getParent(), target.getFileName() + "-", ".tmp");
        try {
            Files.write(temporary, bytes);
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }

    private static String named(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value;
    }
}
