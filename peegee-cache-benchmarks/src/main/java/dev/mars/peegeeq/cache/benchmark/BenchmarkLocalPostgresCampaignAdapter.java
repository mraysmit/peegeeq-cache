package dev.mars.peegeeq.cache.benchmark;

import dev.mars.peegeeq.cache.core.metrics.CacheMetrics;
import dev.mars.peegeeq.cache.pg.repository.PgCacheRepository;
import dev.mars.peegeeq.cache.pg.service.PgCacheService;
import io.vertx.core.Future;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Tuple;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Run-owned namespace adapter for a supplied PostgreSQL pool; pool/server lifecycle remains caller-owned. */
public final class BenchmarkLocalPostgresCampaignAdapter implements BenchmarkCampaignRunner.TargetAdapter {
    private final Pool pool;
    private final String schema;
    private final BenchmarkDeploymentTarget target;
    private final BenchmarkScenarioParameters scenario;
    private final BenchmarkCheckpointWriter.Limits checkpointLimits;
    private final Duration checkpointDelay;
    private final PgCacheService cache;

    public BenchmarkLocalPostgresCampaignAdapter(Pool pool, String schema, BenchmarkDeploymentTarget target,
                                                 BenchmarkScenarioParameters scenario,
                                                 BenchmarkCheckpointWriter.Limits checkpointLimits,
                                                 Duration checkpointDelay) {
        this.pool = Objects.requireNonNull(pool, "pool");
        this.schema = Objects.requireNonNull(schema, "schema");
        if (!schema.matches("[A-Za-z_][A-Za-z0-9_]*")) throw new IllegalArgumentException("Invalid schema");
        this.target = Objects.requireNonNull(target, "target");
        this.scenario = Objects.requireNonNull(scenario, "scenario");
        this.checkpointLimits = Objects.requireNonNull(checkpointLimits, "checkpointLimits");
        this.checkpointDelay = Objects.requireNonNull(checkpointDelay, "checkpointDelay");
        this.cache = new PgCacheService(new PgCacheRepository(pool, schema), new CacheMetrics());
    }

    @Override
    public Future<BenchmarkCampaignRunner.TargetIdentity> verify(BenchmarkDeploymentTarget requested) {
        if (!target.equals(requested)) return Future.failedFuture("Campaign requested a different target");
        return BenchmarkPostgresTargetVerifier.verify(pool, requested);
    }

    @Override
    public Future<Void> reset(BenchmarkCampaignPlan.Manifest manifest) { return clear(manifest.resourceName()); }

    @Override
    public Future<BenchmarkCampaignExecutor.PreparedRun> prepare(BenchmarkCampaignPlan.Manifest manifest) {
        var run = manifest.run();
        long seed = run.workloadSeed() ^ run.configurationIndex() ^ ((long) run.forkIndex() << 32)
                ^ run.repetitionIndex();
        var operation = new BenchmarkCacheOperation(cache, manifest.resourceName(), scenario, seed);
        return operation.prepareDataset().map(ignored -> {
            Instant now = Instant.now();
            var environment = new LinkedHashMap<>(BenchmarkCapabilityInventory.localPostgres().environment());
            environment.put("target.id", target.id());
            environment.put("target.kind", target.kind().name());
            environment.put("resource.namespace", manifest.resourceName());
            environment.put("reset.policy", manifest.reset().name());
            environment.put("claim.scope", "Local disposable PostgreSQL characterisation; not production capacity evidence");
            var initial = new BenchmarkRunEvidence(UUID.randomUUID(), run, now, now,
                    BenchmarkRunEvidence.Status.RUNNING, BenchmarkRunEvidence.Validity.UNASSESSED, "",
                    Map.copyOf(environment), List.of(), List.of());
            var profiles = run.timeline().phases().stream()
                    .filter(phase -> phase.kind() != BenchmarkTimeline.PhaseKind.DRAIN)
                    .map(phase -> new BenchmarkPhaseWorkloadPlan.Profile(phase.name(),
                            phase.kind() == BenchmarkTimeline.PhaseKind.BASELINE
                                    || phase.kind() == BenchmarkTimeline.PhaseKind.RECOVERY
                                    ? Math.max(1, run.parameters().concurrency() / 2) : run.parameters().concurrency(),
                            phase.kind() == BenchmarkTimeline.PhaseKind.BASELINE
                                    || phase.kind() == BenchmarkTimeline.PhaseKind.RECOVERY
                                    ? Math.max(0.001, run.parameters().offeredPerSecond() / 4)
                                    : run.parameters().offeredPerSecond(),
                            new BenchmarkWorkloadMix(List.of(
                                    new BenchmarkWorkloadMix.Weight(BenchmarkProductWorkloads.CACHE_GET, 4),
                                    new BenchmarkWorkloadMix.Weight(BenchmarkProductWorkloads.CACHE_SET, 1)))))
                    .toList();
            var phasePlan = new BenchmarkPhaseWorkloadPlan(run.timeline(), run.parameters(), profiles);
            var options = new BenchmarkManagedExecution.Options("cache-characterisation", "cache request", 64,
                    List.of(100_000L, 250_000L, 500_000L, 1_000_000L, 2_500_000L, 5_000_000L,
                            10_000_000L, 25_000_000L, 50_000_000L, 100_000_000L, 1_000_000_000L),
                    Duration.ofMillis(1), Duration.ofMillis(2), checkpointLimits, checkpointDelay,
                    phasePlan, BenchmarkRetryPolicy.none());
            return new BenchmarkCampaignExecutor.PreparedRun(initial, options, operation);
        });
    }

    @Override
    public Future<Void> cleanup(BenchmarkCampaignPlan.Manifest manifest) { return clear(manifest.resourceName()); }

    private Future<Void> clear(String namespace) {
        return pool.preparedQuery("DELETE FROM \"" + schema + "\".cache_entries WHERE namespace = $1")
                .execute(Tuple.of(namespace)).mapEmpty();
    }
}
