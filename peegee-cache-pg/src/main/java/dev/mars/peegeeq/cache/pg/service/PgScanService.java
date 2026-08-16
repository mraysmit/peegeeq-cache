package dev.mars.peegeeq.cache.pg.service;

import dev.mars.peegeeq.cache.api.exception.CacheException;
import dev.mars.peegeeq.cache.api.exception.CacheStoreException;
import dev.mars.peegeeq.cache.api.model.ScanRequest;
import dev.mars.peegeeq.cache.api.model.ScanResult;
import dev.mars.peegeeq.cache.api.scan.ScanService;
import dev.mars.peegeeq.cache.core.metrics.CacheMetrics;
import dev.mars.peegeeq.cache.core.telemetry.CacheOperation;
import dev.mars.peegeeq.cache.core.validation.CoreValidation;
import dev.mars.peegeeq.cache.pg.repository.PgScanRepository;
import io.vertx.core.Future;

public final class PgScanService implements ScanService {

    private final PgScanRepository repository;
    private final CacheMetrics metrics;

    public PgScanService(PgScanRepository repository) {
        this(repository, new CacheMetrics());
    }

    public PgScanService(PgScanRepository repository, CacheMetrics metrics) {
        this.repository = CoreValidation.requireNonNull(repository, "repository");
        this.metrics = CoreValidation.requireNonNull(metrics, "metrics");
    }

    @Override
    public Future<ScanResult> scan(ScanRequest request) {
        return metrics.observe(CacheOperation.SCAN, () -> {
            try {
                CoreValidation.requireNonNull(request, "request");
                CoreValidation.requireNonBlank(request.namespace(), "namespace");
                if (request.limit() < 1) {
                    throw new IllegalArgumentException("limit must be >= 1");
                }
                if (request.limit() > PgScanRepository.MAX_LIMIT) {
                    throw new IllegalArgumentException(
                            "limit must be <= " + PgScanRepository.MAX_LIMIT);
                }
            } catch (IllegalArgumentException ex) {
                return Future.failedFuture(ex);
            }
            return wrapStoreFailure(repository.scan(request));
        });
    }

    private static <T> Future<T> wrapStoreFailure(Future<T> future) {
        return future.recover(err -> {
            if (err instanceof CacheException || err instanceof IllegalArgumentException) {
                return Future.failedFuture(err);
            }
            return Future.failedFuture(new CacheStoreException("Scan failed", err));
        });
    }
}
