package dev.mars.peegeeq.cache.rest.server;

import dev.mars.peegeeq.cache.api.management.AvailableValue;
import dev.mars.peegeeq.cache.api.management.ManagementAuditQueueState;
import dev.mars.peegeeq.cache.api.management.ManagementExpirySweeperState;
import dev.mars.peegeeq.cache.api.management.ManagementOperationAggregate;
import dev.mars.peegeeq.cache.api.management.ManagementOperationStatus;
import dev.mars.peegeeq.cache.api.management.ManagementRuntimeLifecycleState;
import dev.mars.peegeeq.cache.api.management.ManagementRuntimeMonitoring;
import dev.mars.peegeeq.cache.api.management.ManagementRuntimePoolState;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/** Thread-safe, bounded, low-cardinality runtime observations for management routes. */
public final class ManagementRuntimeMonitor {

    private static final int MAX_OPERATIONS = 256;
    private static final String POOL_UNAVAILABLE =
            "The configured Vert.x pool does not expose this metric";

    private final Clock clock;
    private final LongSupplier nanoTime;
    private final Map<String, MutableAggregate> operations = new HashMap<>();
    private final AtomicLong activeOperations = new AtomicLong();
    private final AtomicLong registeredSetups = new AtomicLong();
    private final AtomicLong activePools = new AtomicLong();
    private final AtomicLong pubSubSubscriptions = new AtomicLong();
    private final AtomicLong sseClients = new AtomicLong();
    private final AtomicLong webSocketClients = new AtomicLong();
    private final AtomicLong retainedPayloadBytes = new AtomicLong();
    private final AtomicLong bufferEvictions = new AtomicLong();
    private final AtomicLong streamResets = new AtomicLong();
    private volatile ManagementRuntimeLifecycleState lifecycleState =
            ManagementRuntimeLifecycleState.NEW;
    private volatile OperationTelemetry operationTelemetry = (operation, failed, elapsedNanos) -> { };

    public ManagementRuntimeMonitor() {
        this(Clock.systemUTC(), System::nanoTime);
    }

    ManagementRuntimeMonitor(Clock clock, LongSupplier nanoTime) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
    }

    public void lifecycle(ManagementRuntimeLifecycleState state) {
        lifecycleState = Objects.requireNonNull(state, "state");
    }

    public Operation begin(String operation) {
        String boundedName = new ManagementOperationAggregate(
                operation, ManagementOperationStatus.ACTIVE, 0, 0, 0).operation();
        MutableAggregate aggregate = requireAggregate(boundedName);
        long startedAt = nanoTime.getAsLong();
        aggregate.begin();
        activeOperations.incrementAndGet();
        return new Operation(aggregate, startedAt);
    }

    void operationTelemetry(OperationTelemetry telemetry) {
        operationTelemetry = Objects.requireNonNull(telemetry, "telemetry");
    }

    public void pubSubSubscriptions(long value) {
        setNonNegative(pubSubSubscriptions, value, "pubSubSubscriptions");
    }

    public void registeredSetups(long value) {
        setNonNegative(registeredSetups, value, "registeredSetups");
    }

    public void activePools(long value) {
        setNonNegative(activePools, value, "activePools");
    }

    public ResourceLease trackPubSubSubscription() {
        return track(pubSubSubscriptions);
    }

    public ResourceLease trackSseClient() {
        return track(sseClients);
    }

    public ResourceLease trackWebSocketClient() {
        return track(webSocketClients);
    }

    public void retainedPayloadBytes(long value) {
        setNonNegative(retainedPayloadBytes, value, "retainedPayloadBytes");
    }

    public void bufferEvicted() {
        bufferEvictions.incrementAndGet();
    }

    public void streamReset() {
        streamResets.incrementAndGet();
    }

    public ResourceSnapshot resourceSnapshot() {
        return new ResourceSnapshot(
                registeredSetups.get(), activePools.get(), pubSubSubscriptions.get(),
                sseClients.get(), webSocketClients.get(),
                retainedPayloadBytes.get(), bufferEvictions.get(), streamResets.get());
    }

    public void streamResources(long sse, long webSockets, long retainedBytes) {
        setNonNegative(sseClients, sse, "sseClients");
        setNonNegative(webSocketClients, webSockets, "webSocketClients");
        setNonNegative(retainedPayloadBytes, retainedBytes, "retainedPayloadBytes");
    }

    public ManagementRuntimeMonitoring snapshot(
            long poolMaximum,
            boolean expirySweeperRunning,
            Instant lastSweepAt,
            ManagementAuditQueueState auditQueue) {
        if (poolMaximum < 1) {
            throw new IllegalArgumentException("poolMaximum must be positive");
        }
        List<ManagementOperationAggregate> operationSnapshots;
        synchronized (operations) {
            operationSnapshots = operations.values().stream()
                    .map(MutableAggregate::snapshot)
                    .sorted(Comparator.comparing(ManagementOperationAggregate::operation))
                    .toList();
        }
        AvailableValue<Long> unavailable = AvailableValue.unavailable(POOL_UNAVAILABLE);
        return new ManagementRuntimeMonitoring(
                clock.instant(),
                lifecycleState,
                new ManagementRuntimePoolState(
                        unavailable,
                        unavailable,
                        unavailable,
                        AvailableValue.available(poolMaximum)),
                activeOperations.get(),
                pubSubSubscriptions.get(),
                sseClients.get(),
                webSocketClients.get(),
                retainedPayloadBytes.get(),
                Objects.requireNonNull(auditQueue, "auditQueue"),
                new ManagementExpirySweeperState(true, expirySweeperRunning, lastSweepAt),
                operationSnapshots);
    }

    private MutableAggregate requireAggregate(String name) {
        synchronized (operations) {
            MutableAggregate existing = operations.get(name);
            if (existing != null) {
                return existing;
            }
            if (operations.size() >= MAX_OPERATIONS) {
                throw new IllegalStateException("runtime operation cardinality limit reached");
            }
            MutableAggregate created = new MutableAggregate(name);
            operations.put(name, created);
            return created;
        }
    }

    private static void setNonNegative(AtomicLong target, long value, String name) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must be non-negative");
        }
        target.set(value);
    }

    private static ResourceLease track(AtomicLong gauge) {
        gauge.incrementAndGet();
        return new ResourceLease(gauge);
    }

    public static final class ResourceLease implements AutoCloseable {
        private final AtomicLong gauge;
        private final AtomicBoolean closed = new AtomicBoolean();

        private ResourceLease(AtomicLong gauge) {
            this.gauge = gauge;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                gauge.decrementAndGet();
            }
        }
    }

    public record ResourceSnapshot(
            long registeredSetups,
            long activePools,
            long pubSubSubscriptions,
            long sseClients,
            long webSocketClients,
            long retainedPayloadBytes,
            long bufferEvictions,
            long streamResets) {
    }

    public final class Operation {
        private final MutableAggregate aggregate;
        private final long startedAt;
        private final AtomicBoolean completed = new AtomicBoolean();

        private Operation(MutableAggregate aggregate, long startedAt) {
            this.aggregate = aggregate;
            this.startedAt = startedAt;
        }

        public void succeeded() {
            complete(false);
        }

        public void failed() {
            complete(true);
        }

        private void complete(boolean failed) {
            if (!completed.compareAndSet(false, true)) {
                return;
            }
            long elapsedNanos = Math.max(0, nanoTime.getAsLong() - startedAt);
            aggregate.complete(failed, elapsedNanos / 1_000_000L);
            activeOperations.decrementAndGet();
            try {
                operationTelemetry.completed(aggregate.name, failed, elapsedNanos);
            } catch (RuntimeException ignored) {
                // Exporter failures must not replace the database operation outcome.
            }
        }
    }

    @FunctionalInterface
    interface OperationTelemetry {
        void completed(String operation, boolean failed, long elapsedNanos);
    }

    private static final class MutableAggregate {
        private final String name;
        private long active;
        private long count;
        private long errors;
        private long latencyMillis;
        private ManagementOperationStatus lastStatus = ManagementOperationStatus.COMPLETE;

        private MutableAggregate(String name) {
            this.name = name;
        }

        private synchronized void begin() {
            active++;
            count++;
        }

        private synchronized void complete(boolean failed, long elapsedMillis) {
            active--;
            if (failed) {
                errors++;
            }
            latencyMillis = saturatedAdd(latencyMillis, elapsedMillis);
            lastStatus = failed
                    ? ManagementOperationStatus.FAILED
                    : ManagementOperationStatus.COMPLETE;
        }

        private synchronized ManagementOperationAggregate snapshot() {
            return new ManagementOperationAggregate(
                    name,
                    active > 0 ? ManagementOperationStatus.ACTIVE : lastStatus,
                    count,
                    errors,
                    latencyMillis);
        }

        private static long saturatedAdd(long left, long right) {
            if (Long.MAX_VALUE - left < right) {
                return Long.MAX_VALUE;
            }
            return left + right;
        }
    }
}
