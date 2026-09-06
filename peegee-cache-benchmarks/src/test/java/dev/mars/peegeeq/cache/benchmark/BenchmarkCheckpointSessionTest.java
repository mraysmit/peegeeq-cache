package dev.mars.peegeeq.cache.benchmark;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.WorkerExecutor;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import static dev.mars.peegeeq.cache.benchmark.BenchmarkRunEvidence.*;
import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(VertxExtension.class)
@io.vertx.junit5.Timeout(value = 30, timeUnit = TimeUnit.SECONDS)
class BenchmarkCheckpointSessionTest {
    @TempDir Path directory;

    @Test
    void countFlushIncludesDiagnosticsAndStopPublishesTerminalMetadata(Vertx vertx, VertxTestContext ctx) {
        var worker = vertx.createSharedWorkerExecutor("cadence-count", 1);
        open(vertx, worker, 2, Duration.ofDays(1)).compose(session -> {
            var first = session.append(measurement(1));
            assertFalse(first.isComplete());
            var diagnostic = session.diagnostic(new Diagnostic(1, "INFO", "first interval"));
            return Future.all(first, diagnostic).compose(ignored -> read(vertx, session)).compose(json -> {
                assertEquals(1, json.getJsonArray("measurements").size());
                assertEquals(1, json.getJsonArray("diagnostics").size());
                assertEquals("RUNNING", json.getJsonObject("execution").getString("status"));
                assertEquals("2", json.getJsonObject("environment").getString("checkpoint.maximumBatchItems"));
                var stopped = session.stop("operator stopped the run");
                assertSame(stopped, session.stop("operator stopped the run"));
                assertTrue(session.append(measurement(2)).failed());
                return stopped.compose(ignored -> read(vertx, session));
            }).map(json -> {
                assertEquals("STOPPED", json.getJsonObject("execution").getString("status"));
                assertEquals("operator stopped the run", json.getJsonObject("execution").getString("detail"));
                assertEquals(1, json.getJsonArray("measurements").size());
                return null;
            });
        }).eventually(worker::close).onComplete(ctx.succeeding(ignored -> ctx.completeNow()));
    }

    @Test
    void timerFlushesPartialBatchWithoutAnotherArrival(Vertx vertx, VertxTestContext ctx) {
        var worker = vertx.createSharedWorkerExecutor("cadence-timer", 1);
        open(vertx, worker, 4, Duration.ofMillis(10)).compose(session -> session.append(measurement(1))
                .compose(ignored -> read(vertx, session)).compose(json -> {
                    assertEquals(1, json.getJsonArray("measurements").size());
                    assertEquals("RUNNING", json.getJsonObject("execution").getString("status"));
                    return session.finish(Status.COMPLETED, Validity.VALID, "measured intervals verified")
                            .compose(ignored -> read(vertx, session));
                }).map(json -> {
                    assertEquals("COMPLETED", json.getJsonObject("execution").getString("status"));
                    assertEquals("VALID", json.getJsonObject("execution").getString("measurementValidity"));
                    return null;
                })).eventually(worker::close).onComplete(ctx.succeeding(ignored -> ctx.completeNow()));
    }

    @Test
    void slowWriterAllowsOnlyOneActiveAndOneStagedBatchThenDrainsStop(Vertx vertx, VertxTestContext ctx) {
        var worker = vertx.createSharedWorkerExecutor("cadence-backpressure", 1);
        var release = new CountDownLatch(1);
        open(vertx, worker, 1, Duration.ofDays(1)).compose(session -> occupy(worker, release, ctx).compose(ignored -> {
            var first = session.append(measurement(1));
            var second = session.append(measurement(2));
            var rejected = session.append(measurement(3));
            assertFalse(first.isComplete());
            assertFalse(second.isComplete());
            assertInstanceOf(RejectedExecutionException.class, rejected.cause());
            assertEquals(2, session.pendingItems());
            var stopped = session.stop("bounded stop");
            assertFalse(stopped.isComplete());
            release.countDown();
            return Future.all(first, second, stopped).compose(done -> read(vertx, session)).map(json -> {
                assertEquals(2, json.getJsonArray("measurements").size());
                assertEquals("STOPPED", json.getJsonObject("execution").getString("status"));
                assertEquals(0, session.pendingItems());
                return null;
            });
        })).eventually(() -> { release.countDown(); return worker.close(); })
                .onComplete(ctx.succeeding(ignored -> ctx.completeNow()));
    }

    @Test
    void writeFailureFailsStagedItemsAndCompletionWithoutClaimingTerminalPublication(Vertx vertx, VertxTestContext ctx) {
        var worker = vertx.createSharedWorkerExecutor("cadence-failure", 1);
        var release = new CountDownLatch(1);
        open(vertx, worker, 1, Duration.ofMillis(10)).compose(session ->
                vertx.executeBlocking(() -> Files.writeString(session.path(), "external owner"))
                .compose(ignored -> occupy(worker, release, ctx))
                .compose(ignored -> {
                    var first = session.append(measurement(1));
                    var second = session.append(measurement(2));
                    var stopped = session.stop("failure stop");
                    release.countDown();
                    return Future.join(first, second, stopped).transform(result -> {
                        assertTrue(result.failed());
                        assertInstanceOf(java.io.IOException.class, first.cause());
                        assertSame(first.cause(), second.cause());
                        assertSame(first.cause(), stopped.cause());
                        assertTrue(session.append(measurement(3)).failed());
                        assertEquals(0, session.pendingItems());
                        return vertx.executeBlocking(() -> {
                            assertEquals("external owner", Files.readString(session.path()));
                            return null;
                        });
                    });
                })).eventually(() -> { release.countDown(); return worker.close(); })
                .onComplete(ctx.succeeding(ignored -> ctx.completeNow()));
    }

    @Test
    void rejectedInputAndInvalidStopLeavePreviouslyAcceptedDataUsable(Vertx vertx, VertxTestContext ctx) {
        var worker = vertx.createSharedWorkerExecutor("cadence-validation", 1);
        open(vertx, worker, 3, Duration.ofDays(1)).compose(session -> {
            var first = session.append(measurement(1));
            assertInstanceOf(IllegalArgumentException.class,
                    session.diagnostic(new Diagnostic(2, "INFO", "x".repeat(100_000))).cause());
            assertTrue(session.stop("").failed());
            assertEquals(1, session.pendingItems());
            var finished = session.finish(Status.FAILED, Validity.INVALID, "workload failed");
            assertTrue(session.stop("different terminal request").failed());
            return Future.all(first, finished).compose(ignored -> read(vertx, session)).map(json -> {
                assertEquals(1, json.getJsonArray("measurements").size());
                assertEquals(0, json.getJsonArray("diagnostics").size());
                assertEquals("FAILED", json.getJsonObject("execution").getString("status"));
                return null;
            });
        }).eventually(worker::close).onComplete(ctx.succeeding(ignored -> ctx.completeNow()));
    }

    @Test
    void invalidCadenceDoesNotReserveAnExecutionFile(Vertx vertx, VertxTestContext ctx) {
        var worker = vertx.createSharedWorkerExecutor("cadence-invalid", 1);
        open(vertx, worker, 2, Duration.ofNanos(1)).transform(result -> {
            assertInstanceOf(IllegalArgumentException.class, result.cause());
            return vertx.executeBlocking(() -> {
                try (var files = Files.list(directory)) { assertEquals(0, files.count()); }
                return null;
            });
        }).eventually(worker::close).onComplete(ctx.succeeding(ignored -> ctx.completeNow()));
    }

    private Future<BenchmarkCheckpointSession> open(Vertx vertx, WorkerExecutor worker, int items, Duration delay) {
        var base = BenchmarkRunJsonWriterTest.evidence(Status.RUNNING, List.of(), "");
        var initial = new BenchmarkRunEvidence(base.executionId(), base.plan(), base.startedAt(), base.updatedAt(),
                base.status(), base.validity(), base.detail(), base.environment(), List.of(), List.of());
        return BenchmarkCheckpointSession.open(vertx, worker, directory, initial,
                new BenchmarkCheckpointWriter.Limits(items, 16_384, 1), delay, Clock.fixed(base.updatedAt(), ZoneOffset.UTC));
    }

    @Test
    void largeTerminalReasonGetsSeparatePublicationWithoutDroppingAcceptedDiagnostic(Vertx vertx, VertxTestContext ctx) {
        var worker = vertx.createSharedWorkerExecutor("cadence-terminal-bytes", 1);
        open(vertx, worker, 3, Duration.ofDays(1)).compose(session -> {
            String message = "diagnostic-" + "d".repeat(10_000);
            String reason = "stop-" + "s".repeat(5_000);
            var accepted = session.diagnostic(new Diagnostic(1, "INFO", message));
            assertFalse(accepted.isComplete());
            return Future.all(accepted, session.stop(reason)).compose(ignored -> read(vertx, session)).map(json -> {
                assertEquals(reason, json.getJsonObject("execution").getString("detail"));
                assertEquals(message, json.getJsonArray("diagnostics").getJsonObject(0).getString("message"));
                assertEquals("STOPPED", json.getJsonObject("execution").getString("status"));
                assertEquals(1, json.getJsonArray("diagnostics").size());
                return null;
            });
        }).eventually(worker::close).onComplete(ctx.succeeding(ignored -> ctx.completeNow()));
    }

    private static Measurement measurement(int index) {
        return BenchmarkCheckpointWriterTest.batch(index, Status.RUNNING).measurements().getFirst();
    }

    private static Future<JsonObject> read(Vertx vertx, BenchmarkCheckpointSession session) {
        return vertx.executeBlocking(() -> new JsonObject(Files.readString(session.path())));
    }

    private static Future<Void> occupy(WorkerExecutor worker, CountDownLatch release, VertxTestContext ctx) {
        Promise<Void> entered = Promise.promise();
        worker.executeBlocking(() -> {
            entered.complete();
            if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Worker was not released");
            return null;
        }).onFailure(ctx::failNow);
        return entered.future();
    }
}
