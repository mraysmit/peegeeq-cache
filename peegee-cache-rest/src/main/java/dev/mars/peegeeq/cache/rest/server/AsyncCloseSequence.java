package dev.mars.peegeeq.cache.rest.server;

import io.vertx.core.Future;
import io.vertx.core.Promise;

import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/** Ordered best-effort asynchronous cleanup that preserves the first failure. */
final class AsyncCloseSequence {

    private AsyncCloseSequence() {
    }

    static Future<Void> closeAll(List<? extends Supplier<Future<Void>>> closeOperations) {
        Objects.requireNonNull(closeOperations, "closeOperations");
        Promise<Void> result = Promise.promise();
        closeNext(List.copyOf(closeOperations), 0, null, result);
        return result.future();
    }

    private static void closeNext(
            List<? extends Supplier<Future<Void>>> closeOperations,
            int index,
            Throwable firstFailure,
            Promise<Void> result) {
        if (index == closeOperations.size()) {
            if (firstFailure == null) {
                result.complete();
            } else {
                result.fail(firstFailure);
            }
            return;
        }

        Future<Void> close;
        try {
            close = Objects.requireNonNull(
                    closeOperations.get(index).get(), "Close operation returned null");
        } catch (Throwable failure) {
            closeNext(closeOperations, index + 1,
                    firstFailure == null ? failure : firstFailure, result);
            return;
        }
        close.onComplete(outcome -> closeNext(
                closeOperations,
                index + 1,
                firstFailure == null && outcome.failed() ? outcome.cause() : firstFailure,
                result));
    }
}
