package dev.mars.peegeeq.cache.rest.server;

import io.vertx.core.Future;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AsyncCloseSequenceTest {

    @Test
    void attemptsEveryCloseInOrderAndReportsTheFirstAsynchronousFailure() {
        List<String> order = new ArrayList<>();
        IllegalStateException first = new IllegalStateException("registry close failed");

        ExecutionException thrown = assertThrows(ExecutionException.class, () -> await(
                AsyncCloseSequence.closeAll(List.of(
                        () -> {
                            order.add("registry");
                            return Future.failedFuture(first);
                        },
                        () -> {
                            order.add("audit");
                            return Future.succeededFuture();
                        },
                        () -> {
                            order.add("vertx");
                            return Future.failedFuture("later failure");
                        }))));

        assertSame(first, thrown.getCause());
        assertEquals(List.of("registry", "audit", "vertx"), order);
    }

    @Test
    void continuesAfterASynchronousCloseThrows() {
        List<String> order = new ArrayList<>();

        assertThrows(ExecutionException.class, () -> await(
                AsyncCloseSequence.closeAll(List.of(
                        () -> {
                            order.add("sessions");
                            throw new IllegalArgumentException("session close failed");
                        },
                        () -> {
                            order.add("vertx");
                            return Future.succeededFuture();
                        }))));

        assertEquals(List.of("sessions", "vertx"), order);
    }

    private static void await(Future<Void> future) throws Exception {
        future.toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }
}
