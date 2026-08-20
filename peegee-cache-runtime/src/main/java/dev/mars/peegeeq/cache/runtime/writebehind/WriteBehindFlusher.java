package dev.mars.peegeeq.cache.runtime.writebehind;

import dev.mars.peegeeq.cache.api.cache.CacheService;
import dev.mars.peegeeq.cache.api.model.CacheKey;
import dev.mars.peegeeq.cache.api.model.CacheSetRequest;
import dev.mars.peegeeq.cache.core.metrics.CacheMetrics;
import dev.mars.peegeeq.cache.core.writebehind.PendingWrite;
import dev.mars.peegeeq.cache.core.writebehind.WriteBehindBuffer;
import dev.mars.peegeeq.cache.runtime.config.WriteBehindConfig;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.LongSupplier;

/** Periodically flushes buffered cache mutations through an existing cache service. */
public final class WriteBehindFlusher {

    private static final Logger log = LoggerFactory.getLogger(WriteBehindFlusher.class);
    private static final Duration DEFAULT_RETRY_DELAY = Duration.ofMillis(50);

    private final Vertx vertx;
    private final CacheService delegate;
    private final WriteBehindBuffer buffer;
    private final WriteBehindConfig config;
    private final LongSupplier nanoTime;
    private final Duration retryDelay;
    private final CacheMetrics metrics;
    private final Object stateLock = new Object();

    private Long timerId;
    private Future<Void> activeFlush;

    public WriteBehindFlusher(
            Vertx vertx,
            CacheService delegate,
            WriteBehindBuffer buffer,
            WriteBehindConfig config) {
        this(vertx, delegate, buffer, config, new CacheMetrics());
    }

    public WriteBehindFlusher(
            Vertx vertx,
            CacheService delegate,
            WriteBehindBuffer buffer,
            WriteBehindConfig config,
            CacheMetrics metrics) {
        this(vertx, delegate, buffer, config, System::nanoTime, DEFAULT_RETRY_DELAY, metrics);
    }

    WriteBehindFlusher(
            Vertx vertx,
            CacheService delegate,
            WriteBehindBuffer buffer,
            WriteBehindConfig config,
            LongSupplier nanoTime,
            Duration retryDelay) {
        this(vertx, delegate, buffer, config, nanoTime, retryDelay, new CacheMetrics());
    }

    WriteBehindFlusher(
            Vertx vertx,
            CacheService delegate,
            WriteBehindBuffer buffer,
            WriteBehindConfig config,
            LongSupplier nanoTime,
            Duration retryDelay,
            CacheMetrics metrics) {
        this.vertx = Objects.requireNonNull(vertx, "vertx");
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.buffer = Objects.requireNonNull(buffer, "buffer");
        this.config = Objects.requireNonNull(config, "config");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
        this.retryDelay = Objects.requireNonNull(retryDelay, "retryDelay");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        if (retryDelay.isNegative()) {
            throw new IllegalArgumentException("retryDelay must be >= 0");
        }
    }

    public void start() {
        synchronized (stateLock) {
            if (timerId != null) {
                return;
            }
            long intervalMillis = Math.max(1, config.flushInterval().toMillis());
            timerId = vertx.setPeriodic(intervalMillis, ignored -> flush());
        }
    }

    public void stop() {
        synchronized (stateLock) {
            if (timerId != null) {
                vertx.cancelTimer(timerId);
                timerId = null;
            }
        }
    }

    public boolean isRunning() {
        synchronized (stateLock) {
            return timerId != null;
        }
    }

    public Future<Void> flush() {
        Promise<Void> promise;
        Future<Void> result;
        List<PendingWrite> batch;
        synchronized (stateLock) {
            if (activeFlush != null) {
                return activeFlush;
            }
            batch = buffer.drain(config.flushBatchSize());
            if (batch.isEmpty()) {
                return Future.succeededFuture();
            }
            promise = Promise.promise();
            result = promise.future();
            activeFlush = result;
        }

        long startedAtNanos = System.nanoTime();
        flushBatch(batch, 0).onComplete(completion -> {
            Throwable failure = completion.failed() ? completion.cause() : null;
            metrics.recordWriteBehindFlush(batch.size(),
                    Duration.ofNanos(Math.max(0, System.nanoTime() - startedAtNanos)), failure);
            if (failure != null) {
                metrics.recordWriteBehindDiscard(batch.size());
                log.atError()
                        .addKeyValue("discarded.entries", batch.size())
                        .addKeyValue("retry.count", config.maxRetries())
                        .setCause(failure)
                        .log("cache.write_behind.flush_failed");
            }
            synchronized (stateLock) {
                if (activeFlush == result) {
                    activeFlush = null;
                }
            }
            promise.handle(completion);
        });
        return result;
    }

    public void requestFlush() {
        flush();
    }

    public Future<Void> drain() {
        Promise<Void> promise = Promise.promise();
        drainNext(promise);
        return promise.future();
    }

    public Future<Void> stopAndDrain() {
        stop();
        return drain()
                .timeout(Math.max(1, config.shutdownDrainTimeout().toMillis()),
                        java.util.concurrent.TimeUnit.MILLISECONDS)
                .recover(failure -> {
                    int discarded = buffer.drain().size();
                    metrics.recordWriteBehindDiscard(discarded);
                    log.atWarn()
                            .addKeyValue("discarded.entries", discarded)
                            .setCause(failure)
                            .log("cache.write_behind.shutdown_drain_incomplete");
                    return Future.succeededFuture();
                });
    }

    private void drainNext(Promise<Void> promise) {
        flush().onComplete(result -> {
            if (result.failed()) {
                promise.fail(result.cause());
            } else if (buffer.isEmpty()) {
                promise.complete();
            } else {
                vertx.runOnContext(ignored -> drainNext(promise));
            }
        });
    }

    private Future<Void> flushBatch(List<PendingWrite> batch, int retryCount) {
        List<CacheKey> deletes = new ArrayList<>();
        List<CacheSetRequest> sets = new ArrayList<>();
        long nowNanos = nanoTime.getAsLong();

        for (PendingWrite write : batch) {
            if (write.operation() == PendingWrite.Operation.DELETE) {
                deletes.add(write.key());
            } else {
                CacheSetRequest adjusted = adjustTtl(write, nowNanos);
                if (adjusted != null) {
                    sets.add(adjusted);
                }
            }
        }

        Future<Void> operation = Future.succeededFuture();
        if (!deletes.isEmpty()) {
            operation = operation.compose(ignored -> delegate.deleteMany(deletes).mapEmpty());
        }
        if (!sets.isEmpty()) {
            operation = operation.compose(ignored -> delegate.setMany(sets).mapEmpty());
        }

        return operation.recover(failure -> {
            if (retryCount >= config.maxRetries()) {
                return Future.failedFuture(failure);
            }
            return retryDelay().compose(ignored -> flushBatch(batch, retryCount + 1));
        });
    }

    private CacheSetRequest adjustTtl(PendingWrite write, long nowNanos) {
        CacheSetRequest request = write.request();
        if (request.ttl() == null) {
            return request;
        }
        long elapsedNanos = Math.max(0, nowNanos - write.acceptedAtNanos());
        Duration remainingTtl = request.ttl().minusNanos(elapsedNanos);
        if (remainingTtl.isZero() || remainingTtl.isNegative()) {
            return null;
        }
        return new CacheSetRequest(
                request.key(),
                request.value(),
                remainingTtl,
                request.mode(),
                request.expectedVersion(),
                request.returnPreviousValue());
    }

    private Future<Void> retryDelay() {
        if (retryDelay.isZero()) {
            return Future.succeededFuture();
        }
        return Future.future(promise -> vertx.setTimer(
                Math.max(1, retryDelay.toMillis()),
                ignored -> promise.complete()));
    }
}
