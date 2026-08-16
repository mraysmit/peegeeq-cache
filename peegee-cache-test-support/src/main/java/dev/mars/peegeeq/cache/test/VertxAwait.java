package dev.mars.peegeeq.cache.test;

import io.vertx.core.Future;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/** Blocking bridge reserved for test and benchmark setup/teardown code. */
public final class VertxAwait {

    private VertxAwait() {
        throw new UnsupportedOperationException("Utility class");
    }

    public static <T> T await(Future<T> future, Duration timeout) throws Exception {
        Objects.requireNonNull(future, "future");
        Objects.requireNonNull(timeout, "timeout");
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        future.onComplete(ar -> {
            if (ar.succeeded()) {
                result.set(ar.result());
            } else {
                failure.set(ar.cause());
            }
            latch.countDown();
        });
        if (!latch.await(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
            throw new TimeoutException("Timed out after " + timeout);
        }
        if (failure.get() != null) {
            if (failure.get() instanceof Exception exception) {
                throw exception;
            }
            throw new RuntimeException(failure.get());
        }
        return result.get();
    }
}
