package dev.mars.peegeeq.cache.rest.server;

import io.vertx.core.Context;
import io.vertx.core.Vertx;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Injectable periodic scheduling boundary for deterministic stream tests. */
@FunctionalInterface
interface ManagementPeriodicScheduler {

    Cancellable schedule(long intervalMillis, Runnable task);

    default Cancellable scheduleOnce(long delayMillis, Runnable task) {
        AtomicReference<Cancellable> scheduled = new AtomicReference<>();
        Cancellable repeating = schedule(delayMillis, () -> {
            Cancellable current = scheduled.get();
            if (current != null) current.cancel();
            task.run();
        });
        scheduled.set(repeating);
        return repeating;
    }

    static ManagementPeriodicScheduler currentVertxContext() {
        return (intervalMillis, task) -> {
            Context context = Vertx.currentContext();
            if (context == null) {
                throw new IllegalStateException("A Vert.x context is required for stream scheduling");
            }
            long timerId = context.owner().setPeriodic(intervalMillis, ignored -> task.run());
            AtomicBoolean cancelled = new AtomicBoolean();
            return () -> {
                if (cancelled.compareAndSet(false, true)) {
                    context.owner().cancelTimer(timerId);
                }
            };
        };
    }

    @FunctionalInterface
    interface Cancellable {
        void cancel();
    }
}
