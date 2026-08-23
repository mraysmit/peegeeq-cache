package dev.mars.peegeeq.cache.rest.server;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ManagementMetricsSamplerTest {

    @Test
    void sharesOnePeriodicSnapshotAcrossAllClientsForASetup() {
        RecordingScheduler scheduler = new RecordingScheduler();
        List<String> snapshots = new ArrayList<>();
        AtomicInteger firstHeartbeats = new AtomicInteger();
        AtomicInteger secondHeartbeats = new AtomicInteger();
        ManagementMetricsSampler sampler = new ManagementMetricsSampler(
                scheduler, snapshots::add);

        AutoCloseable first = sampler.acquire("orders", firstHeartbeats::incrementAndGet);
        AutoCloseable second = sampler.acquire("orders", secondHeartbeats::incrementAndGet);
        assertEquals(1, scheduler.scheduled.get());

        scheduler.fire();
        assertEquals(List.of("orders"), snapshots);
        assertEquals(1, firstHeartbeats.get());
        assertEquals(1, secondHeartbeats.get());

        close(first);
        assertEquals(0, scheduler.cancelled.get());
        close(second);
        assertEquals(1, scheduler.cancelled.get());
    }

    @Test
    void ownsIndependentSamplersAndCancelsThemAtSetupAndServerBoundaries() {
        RecordingScheduler scheduler = new RecordingScheduler();
        ManagementMetricsSampler sampler = new ManagementMetricsSampler(
                scheduler, ignored -> { });
        sampler.acquire("orders", () -> { });
        sampler.acquire("billing", () -> { });
        assertEquals(2, scheduler.scheduled.get());

        sampler.closeSetup("orders").toCompletionStage().toCompletableFuture().join();
        assertEquals(1, scheduler.cancelled.get());
        sampler.close().toCompletionStage().toCompletableFuture().join();
        assertEquals(2, scheduler.cancelled.get());
    }

    private static void close(AutoCloseable closeable) {
        try {
            closeable.close();
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    private static final class RecordingScheduler implements ManagementPeriodicScheduler {
        private final List<Task> tasks = new ArrayList<>();
        private final AtomicInteger scheduled = new AtomicInteger();
        private final AtomicInteger cancelled = new AtomicInteger();

        @Override
        public Cancellable schedule(long intervalMillis, Runnable task) {
            assertEquals(15_000, intervalMillis);
            scheduled.incrementAndGet();
            Task scheduledTask = new Task(task);
            tasks.add(scheduledTask);
            return scheduledTask::cancel;
        }

        private void fire() {
            List.copyOf(tasks).forEach(Task::fire);
        }

        private final class Task {
            private final Runnable task;
            private boolean active = true;

            private Task(Runnable task) {
                this.task = task;
            }

            private void fire() {
                if (active) task.run();
            }

            private void cancel() {
                if (active) {
                    active = false;
                    cancelled.incrementAndGet();
                }
            }
        }
    }
}
