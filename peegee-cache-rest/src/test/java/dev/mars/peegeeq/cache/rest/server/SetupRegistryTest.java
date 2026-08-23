package dev.mars.peegeeq.cache.rest.server;

import dev.mars.peegeeq.cache.api.management.ManagementSecretReference;
import dev.mars.peegeeq.cache.rest.security.SetupTarget;
import dev.mars.peegeeq.cache.rest.security.TlsMode;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SetupRegistryTest {

    @Test
    void testConnectionAlwaysClosesTemporaryRuntimeAndPublishesNothing() throws Exception {
        RecordingFactory factory = new RecordingFactory();
        SetupRegistry registry = new SetupRegistry(factory, reference -> bytes("configured-secret"));
        SetupSecret secret = SetupSecret.owned(bytes("ui-secret"));

        await(registry.test(definition("candidate"), secret));

        assertEquals(1, factory.created.size());
        assertTrue(factory.created.getFirst().closed);
        assertTrue(registry.list().isEmpty());
        assertTrue(secret.isCleared());
    }

    @Test
    void failedRegistrationClosesRuntimeClearsSecretAndLeavesNoEntry() {
        RecordingFactory factory = new RecordingFactory();
        factory.failReadiness = true;
        SetupRegistry registry = new SetupRegistry(factory, reference -> bytes("configured-secret"));
        SetupSecret secret = SetupSecret.owned(bytes("submitted-password"));

        assertThrows(Exception.class, () -> await(registry.register(
                definition("orders"), secret)));

        assertTrue(factory.created.getFirst().closed);
        assertTrue(secret.isCleared());
        assertTrue(registry.list().isEmpty());
    }

    @Test
    void publishesOnlyAfterReadinessThenDetachesReconnectsAndForgetsUiSetup() throws Exception {
        RecordingFactory factory = new RecordingFactory();
        SetupRegistry registry = new SetupRegistry(factory, reference -> bytes("configured-secret"));
        SetupSecret secret = SetupSecret.owned(bytes("session-password"));

        SetupSummary registered = await(registry.register(
                definition("orders"), secret));

        assertTrue(factory.created.getFirst().readyChecked);
        assertEquals(SetupState.CONNECTED, registered.state());
        assertEquals(1, registry.list().size());
        await(registry.detach("orders"));
        assertTrue(factory.created.getFirst().closed);
        assertEquals(SetupState.DETACHED, registry.get("orders").state());

        await(registry.connect("orders"));
        assertEquals(2, factory.created.size());
        assertEquals(SetupState.CONNECTED, registry.get("orders").state());
        await(registry.forget("orders"));
        assertTrue(registry.list().isEmpty());
        assertTrue(secret.isCleared());
    }

    @Test
    void configuredSetupReloadsSecretAndCannotBeForgotten() throws Exception {
        RecordingFactory factory = new RecordingFactory();
        List<String> resolved = new ArrayList<>();
        SetupRegistry registry = new SetupRegistry(factory, reference -> {
            resolved.add(reference.reference());
            return bytes("fresh-secret-" + resolved.size());
        });
        SetupDefinition configured = definition(
                "production", SetupSource.CONFIGURED, new ManagementSecretReference("vault:prod"));

        await(registry.register(configured, null));
        await(registry.detach("production"));
        await(registry.connect("production"));

        assertEquals(List.of("vault:prod", "vault:prod"), resolved);
        SetupRegistryException failure = assertThrows(
                SetupRegistryException.class, () -> await(registry.forget("production")));
        assertEquals("SETUP_ACTION_FORBIDDEN", failure.code());
    }

    @Test
    void serializesDetachAndReconnectForTheSameSetup() throws Exception {
        Promise<Void> closeGate = Promise.promise();
        RecordingFactory factory = new RecordingFactory();
        factory.closeGate = closeGate.future();
        SetupRegistry registry = new SetupRegistry(factory, reference -> bytes("secret"));
        await(registry.register(
                definition("orders"),
                SetupSecret.owned(bytes("password"))));

        Future<Void> detach = registry.detach("orders");
        Future<SetupSummary> reconnect = registry.connect("orders");

        assertEquals(1, factory.created.size());
        closeGate.complete();
        await(detach);
        await(reconnect);
        assertEquals(2, factory.created.size());
        assertEquals(SetupState.CONNECTED, registry.get("orders").state());
    }

    @Test
    void shutdownClosesAllScopesIsIdempotentAndRejectsNewWork() throws Exception {
        RecordingFactory factory = new RecordingFactory();
        SetupRegistry registry = new SetupRegistry(factory, reference -> bytes("secret"));
        await(registry.register(
                definition("one"),
                SetupSecret.owned(bytes("one"))));
        await(registry.register(
                definition("two"),
                SetupSecret.owned(bytes("two"))));

        await(registry.closeAsync());
        await(registry.closeAsync());

        assertTrue(factory.created.stream().allMatch(runtime -> runtime.closed));
        assertTrue(registry.isClosed());
        assertThrows(IllegalStateException.class, () -> registry.register(
                definition("three"),
                SetupSecret.owned(bytes("three"))));
    }

    @Test
    void shutdownWaitsForInflightReconnectAndClosesLateRuntime() throws Exception {
        RecordingFactory factory = new RecordingFactory();
        SetupRegistry registry = new SetupRegistry(factory, reference -> bytes("secret"));
        await(registry.register(
                definition("orders"),
                SetupSecret.owned(bytes("password"))));
        await(registry.detach("orders"));
        io.vertx.core.Promise<Void> readiness = io.vertx.core.Promise.promise();
        factory.readinessGate = readiness.future();

        Future<SetupSummary> reconnect = registry.connect("orders");
        Future<Void> shutdown = registry.closeAsync();
        assertFalse(shutdown.isComplete());
        readiness.complete();

        assertThrows(Exception.class, () -> await(reconnect));
        await(shutdown);
        assertTrue(factory.created.getLast().closed);
    }

    @Test
    void detachAndShutdownCloseRegisteredSetupScopes() throws Exception {
        RecordingFactory factory = new RecordingFactory();
        SetupRegistry registry = new SetupRegistry(factory, reference -> bytes("secret"));
        RecordingScopeLifecycle scopes = new RecordingScopeLifecycle();
        registry.addScopeLifecycle(scopes);
        await(registry.register(
                definition("orders"), SetupSecret.owned(bytes("password"))));

        await(registry.detach("orders"));

        assertEquals(List.of("orders"), scopes.closedSetups);
        await(registry.closeAsync());
        assertEquals(1, scopes.closeCount);
    }

    @Test
    void healthObserversReceiveOnlyStatusTransitions() throws Exception {
        RecordingFactory factory = new RecordingFactory();
        SetupRegistry registry = new SetupRegistry(factory, reference -> bytes("secret"));
        RecordingScopeLifecycle scopes = new RecordingScopeLifecycle();
        registry.addScopeLifecycle(scopes);
        await(registry.register(
                definition("orders"), SetupSecret.owned(bytes("password"))));

        await(registry.health("orders"));
        await(registry.health("orders"));

        assertEquals(List.of(SetupHealthSummary.Status.UP), scopes.healthStatuses);
        await(registry.closeAsync());
    }

    @Test
    void runtimeTelemetryTracksRegisteredAndConnectedSetupsThroughCleanup() throws Exception {
        RecordingFactory factory = new RecordingFactory();
        ManagementRuntimeMonitor monitor = new ManagementRuntimeMonitor();
        SetupRegistry registry = new SetupRegistry(
                factory, reference -> bytes("secret"), monitor);

        await(registry.register(
                definition("orders"), SetupSecret.owned(bytes("password"))));
        assertEquals(1, monitor.resourceSnapshot().registeredSetups());
        assertEquals(1, monitor.resourceSnapshot().activePools());

        await(registry.detach("orders"));
        assertEquals(1, monitor.resourceSnapshot().registeredSetups());
        assertEquals(0, monitor.resourceSnapshot().activePools());

        await(registry.connect("orders"));
        assertEquals(1, monitor.resourceSnapshot().activePools());
        await(registry.forget("orders"));
        assertEquals(0, monitor.resourceSnapshot().registeredSetups());
        assertEquals(0, monitor.resourceSnapshot().activePools());

        await(registry.closeAsync());
        assertEquals(0, monitor.resourceSnapshot().registeredSetups());
        assertEquals(0, monitor.resourceSnapshot().activePools());
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static SetupDefinition definition(String setupId) {
        return definition(setupId, SetupSource.UI_SESSION, null);
    }

    private static SetupDefinition definition(
            String setupId,
            SetupSource source,
            ManagementSecretReference secretReference) {
        return new SetupDefinition(
                setupId,
                "Test " + setupId,
                new SetupTarget("db.internal.example", 5432, "test-ca", TlsMode.VERIFY_FULL),
                "peegeeq",
                "peegee_cache",
                "peegeeq",
                3,
                source,
                secretReference);
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    private static final class RecordingFactory implements SetupRuntimeFactory {
        private final List<RecordingRuntime> created = new ArrayList<>();
        private boolean failReadiness;
        private Future<Void> closeGate = Future.succeededFuture();
        private Future<Void> readinessGate = Future.succeededFuture();

        @Override
        public Future<ManagedSetupRuntime> create(SetupDefinition definition, byte[] secret) {
            RecordingRuntime runtime = new RecordingRuntime(failReadiness, readinessGate, closeGate);
            created.add(runtime);
            assertFalse(secret.length == 0);
            Arrays.fill(secret, (byte) 0);
            return Future.succeededFuture(runtime);
        }
    }

    private static final class RecordingRuntime implements ManagedSetupRuntime {
        private final boolean failReadiness;
        private final Future<Void> closeGate;
        private final Future<Void> readinessGate;
        private boolean readyChecked;
        private boolean closed;

        private RecordingRuntime(
                boolean failReadiness,
                Future<Void> readinessGate,
                Future<Void> closeGate) {
            this.failReadiness = failReadiness;
            this.readinessGate = readinessGate;
            this.closeGate = closeGate;
        }

        @Override
        public Future<Void> verifyReady() {
            readyChecked = true;
            return failReadiness
                    ? Future.failedFuture("schema unavailable")
                    : readinessGate;
        }

        @Override
        public Future<Void> closeAsync() {
            closed = true;
            return closeGate;
        }
    }

    private static final class RecordingScopeLifecycle implements SetupScopeLifecycle {
        private final List<String> closedSetups = new ArrayList<>();
        private final List<SetupHealthSummary.Status> healthStatuses = new ArrayList<>();
        private int closeCount;

        @Override
        public Future<Void> closeSetup(String setupId) {
            closedSetups.add(setupId);
            return Future.succeededFuture();
        }

        @Override
        public Future<Void> healthChanged(String setupId, SetupHealth health) {
            healthStatuses.add(health.status());
            return Future.succeededFuture();
        }

        @Override
        public Future<Void> close() {
            closeCount++;
            return Future.succeededFuture();
        }
    }
}
