package dev.mars.peegeeq.cache.rest.server;

import dev.mars.peegeeq.cache.api.management.AdminCapabilities;
import dev.mars.peegeeq.cache.api.management.ManagementSecretProvider;
import dev.mars.peegeeq.cache.api.management.ManagementService;
import dev.mars.peegeeq.cache.api.PeeGeeCache;
import dev.mars.peegeeq.cache.api.pubsub.PubSubService;
import io.vertx.core.Future;
import io.vertx.core.Promise;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Owns setup runtimes and their secret/resource lifecycles. */
public final class SetupRegistry {

    private static final Logger LOGGER = LoggerFactory.getLogger(SetupRegistry.class);

    private final SetupRuntimeFactory runtimeFactory;
    private final ManagementSecretProvider secretProvider;
    private final Clock clock;
    private final ManagementRuntimeMonitor runtimeMonitor;
    private final Function<AdminCapabilities, SetupCapabilities> capabilityMapper;
    private final Map<String, Entry> entries = new HashMap<>();
    private final Set<String> pendingRegistrations = new HashSet<>();
    private final List<SetupScopeLifecycle> scopeLifecycles = new ArrayList<>();
    private boolean closed;
    private Future<Void> closing;

    public SetupRegistry(SetupRuntimeFactory runtimeFactory, ManagementSecretProvider secretProvider) {
        this(runtimeFactory, secretProvider, Clock.systemUTC(), null, SetupCapabilities::from);
    }

    public SetupRegistry(
            SetupRuntimeFactory runtimeFactory,
            ManagementSecretProvider secretProvider,
            ManagementRuntimeMonitor runtimeMonitor) {
        this(runtimeFactory, secretProvider, Clock.systemUTC(), runtimeMonitor, SetupCapabilities::from);
    }

    SetupRegistry(
            SetupRuntimeFactory runtimeFactory,
            ManagementSecretProvider secretProvider,
            Clock clock) {
        this(runtimeFactory, secretProvider, clock, null, SetupCapabilities::from);
    }

    SetupRegistry(
            SetupRuntimeFactory runtimeFactory,
            ManagementSecretProvider secretProvider,
            Clock clock,
            ManagementRuntimeMonitor runtimeMonitor) {
        this(runtimeFactory, secretProvider, clock, runtimeMonitor, SetupCapabilities::from);
    }

    SetupRegistry(
            SetupRuntimeFactory runtimeFactory,
            ManagementSecretProvider secretProvider,
            Clock clock,
            ManagementRuntimeMonitor runtimeMonitor,
            Function<AdminCapabilities, SetupCapabilities> capabilityMapper) {
        this.runtimeFactory = Objects.requireNonNull(runtimeFactory, "runtimeFactory");
        this.secretProvider = Objects.requireNonNull(secretProvider, "secretProvider");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.runtimeMonitor = runtimeMonitor;
        this.capabilityMapper = Objects.requireNonNull(capabilityMapper, "capabilityMapper");
    }

    public Future<SetupConnectionTest> test(SetupDefinition definition, SetupSecret suppliedSecret) {
        return testInternal(definition, suppliedSecret, true);
    }

    public Future<SetupConnectionTest> testRegistered(String setupId) {
        Entry entry = requireEntry(setupId);
        return testInternal(entry.definition, entry.secret, false);
    }

    private Future<SetupConnectionTest> testInternal(
            SetupDefinition definition,
            SetupSecret suppliedSecret,
            boolean closeSuppliedSecret) {
        Objects.requireNonNull(definition, "definition");
        requireOpen();
        long started = System.nanoTime();
        byte[] secret = resolveSecret(definition, suppliedSecret);
        Future<SetupConnectionTest> operation;
        try {
            operation = runtimeFactory.create(definition, secret)
                    .compose(runtime -> runtime.verifyReady()
                            .map(ignored -> capabilityMapper.apply(runtime.management().capabilities())
                                    .withRuntimeConfiguration(definition.runtimeConfiguration()))
                            .compose(capabilities -> runtime.closeAsync().map(capabilities))
                            .map(capabilities -> connectionTest(started, capabilities))
                            .recover(failure -> runtime.closeAsync()
                                    .recover(closeFailure -> Future.succeededFuture())
                                    .compose(ignored -> Future.failedFuture(failure))));
        } catch (RuntimeException failure) {
            operation = Future.failedFuture(failure);
        }
        return operation.onComplete(ignored -> {
            erase(secret);
            if (closeSuppliedSecret && suppliedSecret != null) {
                suppliedSecret.close();
            }
        });
    }

    private static SetupConnectionTest connectionTest(long started, SetupCapabilities supported) {
        return new SetupConnectionTest(
                true,
                SetupSchemaState.READY,
                "1",
                Math.max(0, (System.nanoTime() - started) / 1_000_000),
                supported.features(),
                supported.limits());
    }

    public Future<SetupSummary> register(SetupDefinition definition, SetupSecret suppliedSecret) {
        Objects.requireNonNull(definition, "definition");
        synchronized (this) {
            requireOpen();
            if (entries.containsKey(definition.setupId()) || !pendingRegistrations.add(definition.setupId())) {
                throw new SetupRegistryException(409, "SETUP_ALREADY_EXISTS", "Setup already exists");
            }
        }
        byte[] secret;
        try {
            secret = resolveSecret(definition, suppliedSecret);
        } catch (RuntimeException failure) {
            registrationFailed(definition.setupId(), suppliedSecret);
            return Future.failedFuture(failure);
        }
        Future<SetupSummary> operation;
        try {
            operation = runtimeFactory.create(definition, secret)
                    .compose(runtime -> runtime.verifyReady()
                            .map(runtime)
                            .recover(failure -> runtime.closeAsync()
                                    .recover(closeFailure -> Future.succeededFuture())
                                    .compose(ignored -> Future.failedFuture(failure))))
                    .map(runtime -> publish(definition, suppliedSecret, runtime));
        } catch (RuntimeException failure) {
            operation = Future.failedFuture(failure);
        }
        return operation.onComplete(result -> {
            erase(secret);
            synchronized (this) {
                pendingRegistrations.remove(definition.setupId());
            }
            if (result.failed() && suppliedSecret != null) {
                suppliedSecret.close();
            }
        });
    }

    public Future<Void> detach(String setupId) {
        Entry entry = requireEntry(setupId);
        return serialize(entry, () -> {
            ManagedSetupRuntime runtime;
            synchronized (entry) {
                runtime = entry.runtime;
                entry.runtime = null;
                entry.state = SetupState.DETACHED;
                entry.connectedAt = null;
            }
            updateRuntimeTelemetry();
            Future<Void> runtimeClose = runtime == null
                    ? Future.succeededFuture() : runtime.closeAsync();
            return Future.all(runtimeClose, closeSetupScopes(setupId)).mapEmpty();
        });
    }

    public synchronized void addScopeLifecycle(SetupScopeLifecycle lifecycle) {
        requireOpen();
        scopeLifecycles.add(Objects.requireNonNull(lifecycle, "lifecycle"));
    }

    public Future<SetupSummary> connect(String setupId) {
        Entry entry = requireEntry(setupId);
        return serialize(entry, () -> connectNow(entry));
    }

    public Future<Void> forget(String setupId) {
        Entry entry = requireEntry(setupId);
        if (entry.definition.source() != SetupSource.UI_SESSION) {
            throw new SetupRegistryException(
                    403, "SETUP_ACTION_FORBIDDEN", "Configured setup cannot be forgotten");
        }
        return detach(setupId).onSuccess(ignored -> {
            synchronized (this) {
                entries.remove(setupId);
                updateRuntimeTelemetryLocked();
            }
            entry.secret.close();
        });
    }

    public synchronized SetupSummary get(String setupId) {
        return requireEntry(setupId).summary();
    }

    public synchronized SetupDetails details(String setupId) {
        return requireEntry(setupId).details();
    }

    public Future<SetupHealth> health(String setupId) {
        Entry entry = requireEntry(setupId);
        ManagedSetupRuntime runtime;
        synchronized (entry) {
            runtime = entry.runtime;
        }
        if (runtime == null) {
            SetupHealth health = new SetupHealth(
                    SetupHealthSummary.Status.DOWN,
                    false,
                    0,
                    clock.instant(),
                    "Setup is detached");
            recordHealthChange(setupId, entry, health);
            return Future.succeededFuture(health);
        }
        return runtime.health().onSuccess(health -> recordHealthChange(setupId, entry, health));
    }

    private void recordHealthChange(String setupId, Entry entry, SetupHealth health) {
        boolean changed;
        synchronized (entry) {
            SetupHealthSummary previous = entry.lastHealth;
            changed = previous == null || previous.status() != health.status();
            entry.lastHealth = health.summary();
        }
        if (!changed) return;
        List<SetupScopeLifecycle> lifecycles;
        synchronized (this) {
            lifecycles = List.copyOf(scopeLifecycles);
        }
        lifecycles.forEach(lifecycle -> {
            try {
                lifecycle.healthChanged(setupId, health).onFailure(ignored ->
                        LOGGER.warn("management.setup.health_event_failed"));
            } catch (RuntimeException failure) {
                LOGGER.warn("management.setup.health_event_failed");
            }
        });
    }

    public synchronized SetupCapabilities capabilities(String setupId) {
        Entry entry = requireEntry(setupId);
        synchronized (entry) {
            if (entry.runtime == null) {
                throw new SetupRegistryException(
                        409, "SETUP_NOT_CONNECTED", "Setup is not connected");
            }
            return capabilityMapper.apply(entry.runtime.management().capabilities())
                    .withRuntimeConfiguration(entry.definition.runtimeConfiguration());
        }
    }

    public ManagementService management(String setupId) {
        Entry entry = requireEntry(setupId);
        synchronized (entry) {
            if (entry.runtime == null) {
                throw new SetupRegistryException(
                        409, "SETUP_NOT_CONNECTED", "Setup is not connected");
            }
            return entry.runtime.management();
        }
    }

    public PubSubService pubSub(String setupId) {
        Entry entry = requireEntry(setupId);
        synchronized (entry) {
            if (entry.runtime == null) {
                throw new SetupRegistryException(
                        409, "SETUP_NOT_CONNECTED", "Setup is not connected");
            }
            return entry.runtime.pubSub();
        }
    }

    /** Returns the complete connected cache facade for backend-capability routes. */
    public PeeGeeCache cache(String setupId) {
        Entry entry = requireEntry(setupId);
        synchronized (entry) {
            if (entry.runtime == null) {
                throw new SetupRegistryException(
                        409, "SETUP_NOT_CONNECTED", "Setup is not connected");
            }
            return entry.runtime.cache();
        }
    }

    public synchronized List<SetupSummary> list() {
        return entries.values().stream()
                .map(Entry::summary)
                .sorted(Comparator.comparing(SetupSummary::setupId))
                .toList();
    }

    public synchronized Future<Void> closeAsync() {
        if (closing != null) {
            return closing;
        }
        closed = true;
        ArrayList<Future<?>> closes = new ArrayList<>();
        for (SetupScopeLifecycle lifecycle : scopeLifecycles) {
            closes.add(closeScope(lifecycle));
        }
        for (Entry entry : entries.values()) {
            if (entry.secret != null) {
                entry.secret.close();
            }
            closes.add(serialize(entry, () -> {
                synchronized (entry) {
                    if (entry.runtime == null) {
                        return Future.succeededFuture();
                    }
                    ManagedSetupRuntime runtime = entry.runtime;
                    entry.runtime = null;
                    entry.state = SetupState.DETACHED;
                    entry.connectedAt = null;
                    return runtime.closeAsync();
                }
            }));
        }
        Future<Void> allClosed = Future.all(closes).mapEmpty();
        closing = allClosed.onSuccess(ignored -> {
            synchronized (this) {
                entries.clear();
                updateRuntimeTelemetryLocked();
            }
        });
        return closing;
    }

    private Future<Void> closeSetupScopes(String setupId) {
        List<SetupScopeLifecycle> lifecycles;
        synchronized (this) {
            lifecycles = List.copyOf(scopeLifecycles);
        }
        List<Future<Void>> closes = lifecycles.stream()
                .map(lifecycle -> closeSetupScope(lifecycle, setupId))
                .toList();
        return closes.isEmpty() ? Future.succeededFuture() : Future.all(closes).mapEmpty();
    }

    private static Future<Void> closeSetupScope(
            SetupScopeLifecycle lifecycle, String setupId) {
        try {
            return lifecycle.closeSetup(setupId);
        } catch (RuntimeException failure) {
            return Future.failedFuture(failure);
        }
    }

    private static Future<Void> closeScope(SetupScopeLifecycle lifecycle) {
        try {
            return lifecycle.close();
        } catch (RuntimeException failure) {
            return Future.failedFuture(failure);
        }
    }

    public synchronized boolean isClosed() {
        return closed;
    }

    private synchronized SetupSummary publish(
            SetupDefinition definition,
            SetupSecret suppliedSecret,
            ManagedSetupRuntime runtime) {
        if (closed) {
            runtime.closeAsync();
            throw new IllegalStateException("Setup registry is closed");
        }
        Instant now = clock.instant();
        Entry entry = new Entry(
                definition, suppliedSecret, runtime, SetupState.CONNECTED, now, now);
        entries.put(definition.setupId(), entry);
        updateRuntimeTelemetryLocked();
        return entry.summary();
    }

    private Future<SetupSummary> connectNow(Entry entry) {
        synchronized (entry) {
            if (entry.runtime != null) {
                return Future.succeededFuture(entry.summary());
            }
        }
        byte[] secret = resolveSecret(entry.definition, entry.secret);
        Future<SetupSummary> operation;
        try {
            operation = runtimeFactory.create(entry.definition, secret)
                    .compose(runtime -> runtime.verifyReady()
                            .map(runtime)
                            .recover(failure -> runtime.closeAsync()
                                    .recover(closeFailure -> Future.succeededFuture())
                                    .compose(ignored -> Future.failedFuture(failure))))
                    .compose(runtime -> publishReconnect(entry, runtime));
        } catch (RuntimeException failure) {
            operation = Future.failedFuture(failure);
        }
        return operation.onComplete(ignored -> erase(secret));
    }

    private Future<SetupSummary> publishReconnect(Entry entry, ManagedSetupRuntime runtime) {
        synchronized (this) {
            if (closed) {
                return runtime.closeAsync().compose(ignored -> Future.failedFuture(
                        new IllegalStateException("Setup registry is closed")));
            }
        }
        SetupSummary summary;
        synchronized (entry) {
            entry.runtime = runtime;
            entry.state = SetupState.CONNECTED;
            entry.connectedAt = clock.instant();
            summary = entry.summary();
        }
        updateRuntimeTelemetry();
        return Future.succeededFuture(summary);
    }

    private synchronized void updateRuntimeTelemetry() {
        updateRuntimeTelemetryLocked();
    }

    private void updateRuntimeTelemetryLocked() {
        if (runtimeMonitor == null) {
            return;
        }
        long active = entries.values().stream()
                .filter(entry -> entry.runtime != null)
                .count();
        runtimeMonitor.registeredSetups(entries.size());
        runtimeMonitor.activePools(active);
    }

    private static <T> Future<T> serialize(Entry entry, Supplier<Future<T>> operation) {
        Promise<T> result = Promise.promise();
        synchronized (entry) {
            Future<Void> predecessor = entry.operationTail;
            Future<Void> current = predecessor
                    .recover(ignored -> Future.succeededFuture())
                    .compose(ignored -> {
                        Future<T> started;
                        try {
                            started = Objects.requireNonNull(operation.get(), "operation future");
                        } catch (RuntimeException failure) {
                            started = Future.failedFuture(failure);
                        }
                        started.onComplete(result);
                        return started.mapEmpty();
                    });
            entry.operationTail = current.recover(ignored -> Future.succeededFuture());
        }
        return result.future();
    }

    private byte[] resolveSecret(SetupDefinition definition, SetupSecret suppliedSecret) {
        if (definition.source() == SetupSource.CONFIGURED) {
            byte[] resolved = secretProvider.resolve(definition.secretReference());
            if (resolved == null || resolved.length == 0) {
                erase(resolved);
                throw new IllegalStateException("Configured setup secret is unavailable");
            }
            return resolved;
        }
        if (suppliedSecret == null) {
            throw new IllegalArgumentException("UI-session setup requires a secret");
        }
        return suppliedSecret.copy();
    }

    private synchronized Entry requireEntry(String setupId) {
        requireOpen();
        Entry entry = entries.get(Objects.requireNonNull(setupId, "setupId"));
        if (entry == null) {
            throw new SetupRegistryException(404, "SETUP_NOT_FOUND", "Setup not found");
        }
        return entry;
    }

    private synchronized void registrationFailed(String setupId, SetupSecret suppliedSecret) {
        pendingRegistrations.remove(setupId);
        if (suppliedSecret != null) {
            suppliedSecret.close();
        }
    }

    private synchronized void requireOpen() {
        if (closed) {
            throw new IllegalStateException("Setup registry is closed");
        }
    }

    private static void erase(byte[] secret) {
        if (secret != null) {
            Arrays.fill(secret, (byte) 0);
        }
    }

    private static final class Entry {
        private final SetupDefinition definition;
        private final SetupSecret secret;
        private ManagedSetupRuntime runtime;
        private SetupState state;
        private final Instant registeredAt;
        private Instant connectedAt;
        private SetupHealthSummary lastHealth;
        private Future<Void> operationTail = Future.succeededFuture();

        private Entry(
                SetupDefinition definition,
                SetupSecret secret,
                ManagedSetupRuntime runtime,
                SetupState state,
                Instant registeredAt,
                Instant connectedAt) {
            this.definition = definition;
            this.secret = secret;
            this.runtime = runtime;
            this.state = state;
            this.registeredAt = registeredAt;
            this.connectedAt = connectedAt;
        }

        private SetupSummary summary() {
            return new SetupSummary(
                    definition.setupId(),
                    definition.displayName(),
                    definition.target().hostname(),
                    definition.target().port(),
                    definition.database(),
                    definition.schema(),
                    definition.target().tlsMode(),
                    definition.source(),
                    state,
                    SetupSchemaState.READY,
                    lastHealth);
        }

        private SetupDetails details() {
            return new SetupDetails(
                    summary(),
                    "1",
                    SetupRuntimeSummary.from(
                            definition.runtimeConfiguration(), definition.poolMaxSize()),
                    registeredAt,
                    connectedAt);
        }
    }
}
