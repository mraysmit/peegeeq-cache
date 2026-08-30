package dev.mars.peegeeq.cache.rest.server;

import dev.mars.peegeeq.cache.api.management.ManagementAuditFingerprinter;
import dev.mars.peegeeq.cache.api.management.ManagementAuditQueueState;
import dev.mars.peegeeq.cache.api.management.ManagementAuditSink;
import dev.mars.peegeeq.cache.api.management.ManagementSecretProvider;
import dev.mars.peegeeq.cache.rest.audit.DurableManagementAuditSink;
import dev.mars.peegeeq.cache.rest.security.BrowserRequestSecurity;
import dev.mars.peegeeq.cache.rest.security.LocalTokenBootstrap;
import dev.mars.peegeeq.cache.rest.security.LocalTokenSessionManager;
import dev.mars.peegeeq.cache.rest.security.ManagementAuthenticationMode;
import dev.mars.peegeeq.cache.rest.security.ManagementRateLimiter;
import dev.mars.peegeeq.cache.rest.security.RateLimitAction;
import dev.mars.peegeeq.cache.rest.security.RateLimitRule;
import dev.mars.peegeeq.cache.rest.security.TargetAddressResolver;
import dev.mars.peegeeq.cache.rest.security.TrustProfileCertificateResolver;
import dev.mars.peegeeq.cache.rest.security.TrustedProxyAuthenticator;
import dev.mars.peegeeq.cache.rest.security.TrustedProxySessionManager;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import io.vertx.core.Future;
import io.vertx.core.Vertx;

import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/** Production composition root for the complete management HTTP runtime. */
public final class ManagementServerApplication {

    private static final int AUDIT_RESERVATION_CAPACITY = 10_000;
    private static final int ACTIVITY_CAPACITY = 10_000;

    private final ManagementHttpServer server;
    private final AtomicReference<String> bootstrapToken;

    private ManagementServerApplication(ManagementHttpServer server, String bootstrapToken) {
        this.server = server;
        this.bootstrapToken = new AtomicReference<>(bootstrapToken);
    }

    /**
     * Opens the durable audit journal, assembles every route, and starts the server.
     * The local bootstrap token can subsequently be taken exactly once.
     */
    public static Future<ManagementServerApplication> start(
            ManagementServerConfiguration configuration,
            ManagementSecretProvider secrets,
            TargetAddressResolver targetResolver,
            TrustProfileCertificateResolver trustProfiles,
            MeterRegistry meterRegistry) {
        return start(
                configuration, secrets, targetResolver, trustProfiles, meterRegistry,
                Clock.systemUTC());
    }

    static Future<ManagementServerApplication> start(
            ManagementServerConfiguration configuration,
            ManagementSecretProvider secrets,
            TargetAddressResolver targetResolver,
            TrustProfileCertificateResolver trustProfiles,
            MeterRegistry meterRegistry,
            Clock clock) {
        Objects.requireNonNull(configuration, "configuration");
        Objects.requireNonNull(secrets, "secrets");
        Objects.requireNonNull(targetResolver, "targetResolver");
        Objects.requireNonNull(trustProfiles, "trustProfiles");
        Objects.requireNonNull(meterRegistry, "meterRegistry");
        Objects.requireNonNull(clock, "clock");

        ManagementRuntimeMonitor runtimeMonitor = new ManagementRuntimeMonitor(clock, System::nanoTime);
        MicrometerManagementTelemetry telemetry = new MicrometerManagementTelemetry(
                meterRegistry, runtimeMonitor);
        ManagementAuditFingerprinter fingerprinter;
        try {
            fingerprinter = new ManagementAuditFingerprinter(
                    configuration.auditFingerprintSecret(),
                    secrets,
                    configuration.auditFingerprintSecret().reference(),
                    128);
        } catch (RuntimeException failure) {
            return Future.failedFuture(failure);
        }
        Vertx vertx = Vertx.vertx();
        return DurableManagementAuditSink.openWithTelemetry(
                        configuration.auditJournal(), AUDIT_RESERVATION_CAPACITY, telemetry)
                .compose(durable -> assembleAndStart(
                        vertx,
                        configuration,
                        secrets,
                        targetResolver,
                        trustProfiles,
                        fingerprinter,
                        durable,
                        telemetry,
                        runtimeMonitor,
                        clock))
                .recover(failure -> vertx.close()
                        .recover(ignored -> Future.succeededFuture())
                        .compose(ignored -> Future.failedFuture(failure)));
    }

    /** Returns the local one-time bootstrap token to the controlling process once. */
    public Optional<String> takeBootstrapToken() {
        return Optional.ofNullable(bootstrapToken.getAndSet(null));
    }

    public boolean isStarted() {
        return server.isStarted();
    }

    public Future<Void> closeAsync() {
        bootstrapToken.set(null);
        return server.stop();
    }

    private static Future<ManagementServerApplication> assembleAndStart(
            Vertx vertx,
            ManagementServerConfiguration configuration,
            ManagementSecretProvider secrets,
            TargetAddressResolver targetResolver,
            TrustProfileCertificateResolver trustProfiles,
            ManagementAuditFingerprinter fingerprinter,
            DurableManagementAuditSink durable,
            MicrometerManagementTelemetry telemetry,
            ManagementRuntimeMonitor runtimeMonitor,
            Clock clock) {
        try {
            ManagementActivityStore activity = new ManagementActivityStore(ACTIVITY_CAPACITY);
            ManagementLiveEventHub liveEvents = new ManagementLiveEventHub(
                    clock, Duration.ofMinutes(5), ACTIVITY_CAPACITY);
            ManagementAuditSink liveAudit = new LivePublishingManagementAuditSink(
                    durable, activity, liveEvents);
            PostgresSetupRuntimeFactory runtimeFactory = new PostgresSetupRuntimeFactory(
                    configuration.targetPolicy(),
                    targetResolver,
                    trustProfiles,
                    Duration.ofSeconds(10),
                    liveAudit,
                    fingerprinter,
                    clock,
                    () -> UUID.randomUUID().toString());
            SetupRegistry registry = new SetupRegistry(runtimeFactory, secrets, clock, runtimeMonitor);
            BrowserRequestSecurity browserSecurity = new BrowserRequestSecurity(
                    configuration.originPolicy());
            ManagementRateLimiter rateLimiter = defaultRateLimiter(clock, telemetry);
            Authentication authentication = authentication(configuration, browserSecurity);

            SetupInspectionRoutes inspections = new SetupInspectionRoutes(
                    registry,
                    authentication.requestAuthenticator(),
                    runtimeMonitor,
                    () -> new ManagementAuditQueueState(
                            durable.pendingReservations(),
                            AUDIT_RESERVATION_CAPACITY,
                            durable.isMutationReady()),
                    activity);
            ManagementPubSubSubscriptions subscriptions = new ManagementPubSubSubscriptions(
                    clock,
                    configuration.limits().maximumSubscriptionsPerActor(),
                    100,
                    500,
                    1024L * 1024,
                    64L * 1024 * 1024,
                    runtimeMonitor);
            ManagementRequestRouter router = ManagementRequestRouter.firstOf(
                    metricsRoutes(telemetry),
                    authentication.sessionRoutes(),
                    new SetupMutationRoutes(
                            registry,
                            authentication.requestAuthenticator(),
                            browserSecurity,
                            liveAudit,
                            rateLimiter,
                            clock,
                            activity),
                    new SetupReadRoutes(registry, authentication.requestAuthenticator()),
                    new SetupAdministrationRoutes(
                            registry,
                            authentication.requestAuthenticator(),
                            browserSecurity,
                            rateLimiter,
                            clock),
                    new PubSubRoutes(
                            registry,
                            authentication.requestAuthenticator(),
                            browserSecurity,
                            liveAudit,
                            fingerprinter,
                            rateLimiter,
                            clock,
                            subscriptions,
                            ManagementPeriodicScheduler.currentVertxContext(),
                            runtimeMonitor),
                    new LiveMonitoringRoutes(
                            registry,
                            authentication.requestAuthenticator(),
                            browserSecurity,
                            inspections,
                            liveEvents,
                            ManagementPeriodicScheduler.currentVertxContext(),
                            clock,
                            runtimeMonitor),
                    inspections);
            ApplicationResources resources = new ApplicationResources(
                    registry, durable, authentication.sessions(), vertx);
            ManagementHttpServer server = new ManagementHttpServer(
                    vertx, configuration, resources, router, telemetry);
            ManagementServerApplication application = new ManagementServerApplication(
                    server, authentication.bootstrapToken());
            return server.start().map(application);
        } catch (RuntimeException failure) {
            return durable.closeAsync()
                    .recover(ignored -> Future.succeededFuture())
                    .compose(ignored -> Future.failedFuture(failure));
        }
    }

    private static Authentication authentication(
            ManagementServerConfiguration configuration,
            BrowserRequestSecurity browserSecurity) {
        if (configuration.authenticationMode() == ManagementAuthenticationMode.LOCAL_TOKEN) {
            LocalTokenBootstrap bootstrap = LocalTokenSessionManager.start(configuration.localToken());
            return new Authentication(
                    new LocalSessionRoutes(
                            bootstrap.manager(), browserSecurity, configuration.maximumRequestBytes()),
                    new LocalSessionRequestAuthenticator(bootstrap.manager()),
                    bootstrap.manager(),
                    bootstrap.token());
        }
        TrustedProxyAuthenticator proxyAuthenticator = new TrustedProxyAuthenticator(
                configuration.trustedProxy());
        TrustedProxySessionManager sessions = TrustedProxySessionManager.createDefault();
        return new Authentication(
                new TrustedProxySessionRoutes(
                        proxyAuthenticator, sessions, configuration.originPolicy()),
                new TrustedProxySessionRequestAuthenticator(proxyAuthenticator, sessions),
                sessions,
                null);
    }

    private static ManagementRateLimiter defaultRateLimiter(
            Clock clock, MicrometerManagementTelemetry telemetry) {
        EnumMap<RateLimitAction, RateLimitRule> rules = new EnumMap<>(RateLimitAction.class);
        Arrays.stream(RateLimitAction.values()).forEach(action -> rules.put(
                action, new RateLimitRule(30, 100, Duration.ofMinutes(1))));
        return new ManagementRateLimiter(rules, 10_000, clock, telemetry);
    }

    private static ManagementRequestRouter metricsRoutes(
            MicrometerManagementTelemetry telemetry) {
        MeterRegistry registry = telemetry.meterRegistry();
        return registry instanceof PrometheusMeterRegistry prometheus
                ? new PrometheusMetricsRoutes(prometheus)
                : ManagementRequestRouter.none();
    }

    private record Authentication(
            ManagementRequestRouter sessionRoutes,
            ManagementRequestAuthenticator requestAuthenticator,
            AutoCloseable sessions,
            String bootstrapToken) {
    }

    private static final class ApplicationResources implements ManagementServerResources {
        private final SetupRegistry registry;
        private final DurableManagementAuditSink audit;
        private final AutoCloseable sessions;
        private final Vertx vertx;

        private ApplicationResources(
                SetupRegistry registry,
                DurableManagementAuditSink audit,
                AutoCloseable sessions,
                Vertx vertx) {
            this.registry = registry;
            this.audit = audit;
            this.sessions = sessions;
            this.vertx = vertx;
        }

        @Override
        public Future<Void> start() {
            return Future.succeededFuture();
        }

        @Override
        public boolean ready() {
            return audit.isMutationReady();
        }

        @Override
        public Future<Void> closeAsync() {
            return AsyncCloseSequence.closeAll(java.util.List.of(
                    registry::closeAsync,
                    audit::closeAsync,
                    this::closeSessions,
                    vertx::close));
        }

        private Future<Void> closeSessions() {
            try {
                sessions.close();
                return Future.succeededFuture();
            } catch (Exception failure) {
                return Future.failedFuture(failure);
            }
        }
    }
}
