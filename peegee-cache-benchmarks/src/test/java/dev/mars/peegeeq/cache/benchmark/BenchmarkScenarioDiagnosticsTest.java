package dev.mars.peegeeq.cache.benchmark;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.time.Duration;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(VertxExtension.class)
class BenchmarkScenarioDiagnosticsTest {

    @Test
    void timedOutOperationIdentifiesItsScenarioAndPreservesTheCause(
            Vertx vertx, VertxTestContext ctx) {
        BenchmarkConfig config = new BenchmarkConfig(
                1, 2, Duration.ZERO, Duration.ofMillis(100), 1,
                Duration.ofMillis(1), Duration.ofSeconds(1), Duration.ofSeconds(1), 100);

        CacheBenchmarkMain.runSustained(
                        "diagnostic-timeout", config, () -> Promise.<Void>promise().future())
                .onSuccess(ignored -> ctx.failNow("Expected benchmark operation to time out"))
                .onFailure(failure -> ctx.verify(() -> {
                    assertInstanceOf(IllegalStateException.class, failure);
                    assertTrue(failure.getMessage().contains("diagnostic-timeout"));
                    assertTrue(failure.getMessage().contains("timed out"));
                    assertInstanceOf(TimeoutException.class, failure.getCause());
                    ctx.completeNow();
                }));
    }

    @Test
    void warmupOperationsAreExecutedButExcludedFromMeasurements(
            Vertx vertx, VertxTestContext ctx) {
        BenchmarkConfig config = new BenchmarkConfig(
                1, 2, Duration.ofMillis(40), Duration.ofMillis(40), 1,
                Duration.ofMillis(100), Duration.ofSeconds(1), Duration.ofSeconds(1), 100);
        AtomicInteger invocations = new AtomicInteger();

        CacheBenchmarkMain.runSustained("warmup-contract", config, () -> {
                    invocations.incrementAndGet();
                    return Future.future(promise -> vertx.setTimer(2, ignored -> promise.complete()));
                })
                .onComplete(ctx.succeeding(snapshot -> ctx.verify(() -> {
                    assertTrue(snapshot.operations() > 0);
                    assertTrue(invocations.get() > snapshot.operations(),
                            "Warm-up calls must execute without entering the measured histogram");
                    ctx.completeNow();
                })));
    }
}
