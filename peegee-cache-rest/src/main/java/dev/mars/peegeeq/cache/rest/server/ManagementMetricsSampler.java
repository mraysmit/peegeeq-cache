package dev.mars.peegeeq.cache.rest.server;

import io.vertx.core.Future;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

/** One bounded periodic database snapshot producer per setup, shared by every metrics client. */
final class ManagementMetricsSampler implements SetupScopeLifecycle {

    private static final Logger LOGGER = LoggerFactory.getLogger(ManagementMetricsSampler.class);
    private static final long INTERVAL_MILLIS = 15_000;

    private final ManagementPeriodicScheduler scheduler;
    private final Consumer<String> snapshot;
    private final Map<String, SetupSampler> samplers = new HashMap<>();
    private boolean closed;

    ManagementMetricsSampler(
            ManagementPeriodicScheduler scheduler, Consumer<String> snapshot) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
    }

    synchronized Lease acquire(String setupId, Runnable heartbeat) {
        Objects.requireNonNull(setupId, "setupId");
        Objects.requireNonNull(heartbeat, "heartbeat");
        if (closed) throw new IllegalStateException("Metrics sampler is closed");
        SetupSampler sampler = samplers.get(setupId);
        if (sampler == null) {
            sampler = new SetupSampler();
            sampler.periodic = scheduler.schedule(INTERVAL_MILLIS, () -> tick(setupId));
            samplers.put(setupId, sampler);
        }
        String clientId = UUID.randomUUID().toString();
        sampler.heartbeats.put(clientId, heartbeat);
        return new Lease(setupId, clientId);
    }

    @Override
    public synchronized Future<Void> closeSetup(String setupId) {
        SetupSampler removed = samplers.remove(setupId);
        if (removed != null) removed.close();
        return Future.succeededFuture();
    }

    @Override
    public synchronized Future<Void> close() {
        if (closed) return Future.succeededFuture();
        closed = true;
        samplers.values().forEach(SetupSampler::close);
        samplers.clear();
        return Future.succeededFuture();
    }

    private void tick(String setupId) {
        List<Runnable> heartbeats;
        synchronized (this) {
            SetupSampler sampler = samplers.get(setupId);
            if (sampler == null) return;
            heartbeats = List.copyOf(sampler.heartbeats.values());
        }
        heartbeats.forEach(this::heartbeat);
        try {
            snapshot.accept(setupId);
        } catch (RuntimeException failure) {
            LOGGER.warn("setup_id={} management.metrics_sampler.snapshot_failed", setupId);
        }
    }

    private void heartbeat(Runnable heartbeat) {
        try {
            heartbeat.run();
        } catch (RuntimeException failure) {
            LOGGER.warn("management.metrics_sampler.heartbeat_failed");
        }
    }

    final class Lease implements AutoCloseable {
        private final String setupId;
        private final String clientId;
        private boolean released;

        private Lease(String setupId, String clientId) {
            this.setupId = setupId;
            this.clientId = clientId;
        }

        @Override
        public void close() {
            synchronized (ManagementMetricsSampler.this) {
                if (released) return;
                released = true;
                SetupSampler sampler = samplers.get(setupId);
                if (sampler == null) return;
                sampler.heartbeats.remove(clientId);
                if (sampler.heartbeats.isEmpty()) {
                    samplers.remove(setupId);
                    sampler.close();
                }
            }
        }
    }

    private static final class SetupSampler {
        private final Map<String, Runnable> heartbeats = new HashMap<>();
        private ManagementPeriodicScheduler.Cancellable periodic;

        private void close() {
            periodic.cancel();
            heartbeats.clear();
        }
    }
}
