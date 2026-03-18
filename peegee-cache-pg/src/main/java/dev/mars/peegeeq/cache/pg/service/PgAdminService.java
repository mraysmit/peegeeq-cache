package dev.mars.peegeeq.cache.pg.service;

import dev.mars.peegeeq.cache.api.admin.AdminService;
import dev.mars.peegeeq.cache.api.model.EntryStats;
import dev.mars.peegeeq.cache.api.model.MetricsSnapshot;
import dev.mars.peegeeq.cache.core.metrics.CacheMetrics;
import dev.mars.peegeeq.cache.core.validation.CoreValidation;
import dev.mars.peegeeq.cache.pg.repository.PgAdminRepository;
import io.vertx.core.Future;

public class PgAdminService implements AdminService {

    private final PgAdminRepository repository;
    private final CacheMetrics metrics;

    public PgAdminService(PgAdminRepository repository, CacheMetrics metrics) {
        this.repository = CoreValidation.requireNonNull(repository, "repository");
        this.metrics = CoreValidation.requireNonNull(metrics, "metrics");
    }

    @Override
    public Future<EntryStats> entryStats(String namespace) {
        CoreValidation.requireNonNull(namespace, "namespace");
        return repository.entryStats(namespace);
    }

    @Override
    public MetricsSnapshot metrics() {
        return metrics.snapshot();
    }
}
