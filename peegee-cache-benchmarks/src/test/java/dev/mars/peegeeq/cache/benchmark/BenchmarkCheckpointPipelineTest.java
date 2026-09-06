package dev.mars.peegeeq.cache.benchmark;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.WorkerExecutor;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static dev.mars.peegeeq.cache.benchmark.BenchmarkCheckpointWriterTest.*;
import static dev.mars.peegeeq.cache.benchmark.BenchmarkRunEvidence.Status.*;
import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(VertxExtension.class)
@Timeout(30)
class BenchmarkCheckpointPipelineTest {
    @TempDir Path directory;

    @Test
    void rejectsAtCapacityAndDrainsWithoutInventingTerminalStatus(Vertx vertx, VertxTestContext context) throws Exception {
        var writer = BenchmarkCheckpointWriter.create(directory, batch(0, RUNNING), limits());
        var worker = vertx.createSharedWorkerExecutor("checkpoint-bounded", 1);
        var release = occupy(worker);
        var pipeline = new BenchmarkCheckpointPipeline(worker, writer, 2, 32_768);
        try {
            var first = pipeline.submit(batch(1, RUNNING));
            var second = pipeline.submit(batch(2, RUNNING));
            var rejected = pipeline.submit(batch(3, RUNNING));
            assertTrue(rejected.failed());
            assertInstanceOf(java.util.concurrent.RejectedExecutionException.class, rejected.cause());
            assertFalse(first.isComplete());
            assertFalse(second.isComplete());
            assertEquals(2, pipeline.pendingCount());
            assertEquals(1, pipeline.rejectedCount());
            var closed = pipeline.close();
            assertFalse(closed.isComplete());
            release.countDown();
            Future.all(first, second, closed).compose(ignored -> vertx.executeBlocking(() -> {
                var json = new JsonObject(Files.readString(writer.path()));
                assertEquals(2, json.getJsonArray("measurements").size());
                assertEquals("RUNNING", json.getJsonObject("execution").getString("status"));
                assertEquals(0, pipeline.pendingCount());
                assertEquals(0, pipeline.pendingBytes());
                assertTrue(pipeline.submit(batch(3, RUNNING)).failed());
                return null;
            })).eventually(worker::close).onComplete(context.succeeding(ignored -> context.completeNow()));
        } finally { release.countDown(); }
    }

    @Test
    void byteLimitRejectsWithoutConsumingCapacityAndCanBeRetried(Vertx vertx, VertxTestContext context) throws Exception {
        var writer = BenchmarkCheckpointWriter.create(directory, batch(0, RUNNING), limits());
        var worker = vertx.createSharedWorkerExecutor("checkpoint-bytes", 1);
        var release = occupy(worker);
        int bytes = writer.prepare(batch(1, RUNNING)).bytes;
        var pipeline = new BenchmarkCheckpointPipeline(worker, writer, 10, bytes);
        try {
            var first = pipeline.submit(batch(1, RUNNING));
            var rejected = pipeline.submit(batch(2, RUNNING));
            assertTrue(rejected.failed());
            assertInstanceOf(java.util.concurrent.RejectedExecutionException.class, rejected.cause());
            assertEquals(1, pipeline.pendingCount());
            assertEquals(bytes, pipeline.pendingBytes());
            release.countDown();
            first.compose(ignored -> pipeline.submit(batch(2, RUNNING)))
                    .compose(ignored -> pipeline.close()).eventually(worker::close)
                    .onComplete(context.succeeding(ignored -> context.completeNow()));
        } finally { release.countDown(); }
    }

    @Test
    void writeFailureFailsEveryAcceptedFutureAndStopsAdmission(Vertx vertx, VertxTestContext context) throws Exception {
        var writer = BenchmarkCheckpointWriter.create(directory, batch(0, RUNNING), limits());
        var worker = vertx.createSharedWorkerExecutor("checkpoint-failure", 1);
        var release = occupy(worker);
        var pipeline = new BenchmarkCheckpointPipeline(worker, writer, 3, 49_152);
        try {
            var first = pipeline.submit(batch(1, RUNNING));
            var second = pipeline.submit(batch(2, RUNNING));
            Files.writeString(writer.path(), "external change");
            var closed = pipeline.close();
            release.countDown();
            Future.join(first, second, closed).transform(result -> {
                context.verify(() -> {
                    assertTrue(result.failed());
                    assertInstanceOf(java.io.IOException.class, first.cause());
                    assertSame(first.cause(), second.cause());
                    assertSame(first.cause(), closed.cause());
                    assertEquals(0, pipeline.pendingCount());
                    assertEquals(0, pipeline.pendingBytes());
                    assertTrue(pipeline.submit(batch(3, RUNNING)).failed());
                });
                return vertx.executeBlocking(() -> {
                    assertEquals("external change", Files.readString(writer.path()));
                    return null;
                });
            }).eventually(worker::close).onComplete(context.succeeding(ignored -> context.completeNow()));
        } finally { release.countDown(); }
    }

    @Test
    void workerSubmissionFailureDoesNotLeaveAcceptedWorkPending(Vertx vertx, VertxTestContext context) throws Exception {
        var writer = BenchmarkCheckpointWriter.create(directory, batch(0, RUNNING), limits());
        var worker = vertx.createSharedWorkerExecutor("checkpoint-closed", 1);
        var pipeline = new BenchmarkCheckpointPipeline(worker, writer, 1, 16_384);
        worker.close().compose(ignored -> pipeline.submit(batch(1, RUNNING)))
                .onComplete(context.failing(failure -> {
                    context.verify(() -> {
                        assertEquals(0, pipeline.pendingCount());
                        assertTrue(pipeline.close().failed());
                    });
                    context.completeNow();
                }));
    }

    private static CountDownLatch occupy(WorkerExecutor worker) throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        worker.executeBlocking(() -> {
            entered.countDown();
            if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Test did not release worker");
            return null;
        });
        assertTrue(entered.await(5, TimeUnit.SECONDS), "Real worker must enter before admission assertions");
        return release;
    }
}
