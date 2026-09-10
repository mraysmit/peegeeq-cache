package dev.mars.peegeeq.cache.rest.server;

import dev.mars.peegeeq.cache.core.telemetry.CacheTelemetry;
import dev.mars.peegeeq.cache.api.management.ManagementAuditFingerprinter;
import dev.mars.peegeeq.cache.api.management.ManagementAuditSink;
import dev.mars.peegeeq.cache.pg.config.PgCacheStoreConfig;
import dev.mars.peegeeq.cache.rest.security.SetupTarget;
import dev.mars.peegeeq.cache.rest.security.SetupTargetPolicy;
import dev.mars.peegeeq.cache.rest.security.TargetAddressResolver;
import dev.mars.peegeeq.cache.rest.security.TrustProfileCertificateResolver;
import dev.mars.peegeeq.cache.runtime.bootstrap.PeeGeeCacheBootstrapOptions;
import dev.mars.peegeeq.cache.runtime.bootstrap.PeeGeeCaches;
import dev.mars.peegeeq.cache.runtime.bootstrap.SchemaBootstrapMode;
import dev.mars.peegeeq.cache.runtime.config.PeeGeeCacheConfig;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.dns.AddressResolverOptions;
import io.vertx.core.net.ClientSSLOptions;
import io.vertx.core.net.PemTrustOptions;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.pgclient.SslMode;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;

import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/** Creates lifecycle-owned PeeGeeQ runtimes over policy-validated, address-pinned TLS pools. */
public final class PostgresSetupRuntimeFactory implements SetupRuntimeFactory {

    private final SetupTargetPolicy policy;
    private final TargetAddressResolver resolver;
    private final TrustProfileCertificateResolver trustProfiles;
    private final Duration connectTimeout;
    private final ManagementAuditSink auditSink;
    private final ManagementAuditFingerprinter auditFingerprinter;
    private final Clock auditClock;
    private final Supplier<String> auditEventIdSupplier;
    private final byte[] cursorKey = cursorKey();

    public PostgresSetupRuntimeFactory(
            SetupTargetPolicy policy,
            TargetAddressResolver resolver,
            TrustProfileCertificateResolver trustProfiles,
            Duration connectTimeout,
            ManagementAuditSink auditSink,
            ManagementAuditFingerprinter auditFingerprinter,
            Clock auditClock,
            Supplier<String> auditEventIdSupplier) {
        this.policy = Objects.requireNonNull(policy, "policy");
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.trustProfiles = Objects.requireNonNull(trustProfiles, "trustProfiles");
        this.connectTimeout = Objects.requireNonNull(connectTimeout, "connectTimeout");
        if (connectTimeout.isZero() || connectTimeout.isNegative()
                || connectTimeout.toMillis() > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("connectTimeout must be positive and fit milliseconds");
        }
        this.auditSink = Objects.requireNonNull(auditSink, "auditSink");
        this.auditFingerprinter = Objects.requireNonNull(auditFingerprinter, "auditFingerprinter");
        this.auditClock = Objects.requireNonNull(auditClock, "auditClock");
        this.auditEventIdSupplier = Objects.requireNonNull(auditEventIdSupplier, "auditEventIdSupplier");
    }

    @Override
    public Future<ManagedSetupRuntime> create(SetupDefinition definition, byte[] secret) {
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(secret, "secret");
        SetupTarget target = definition.target();
        List<InetAddress> answers;
        try {
            answers = List.copyOf(resolver.resolve(target.hostname()));
        } catch (Exception failure) {
            return Future.failedFuture(failure);
        }
        policy.validate(target.hostname(), target.port(), answers, target.trustProfileId());
        InetAddress pinnedAddress = answers.getFirst();
        Buffer certificate = Objects.requireNonNull(
                trustProfiles.resolve(target.trustProfileId()), "Unknown trust profile");
        String password;
        try {
            password = decodeSecret(secret);
        } catch (IllegalArgumentException failure) {
            return Future.failedFuture(failure);
        }

        Vertx runtime = createPinnedVertx(pinnedAddress, target.hostname());
        PgConnectOptions connectOptions = new PgConnectOptions()
                .setHost(target.hostname())
                .setPort(target.port())
                .setDatabase(definition.database())
                .setUser(definition.username())
                .setPassword(password)
                .setSslMode(SslMode.VERIFY_FULL)
                .setReconnectAttempts(0)
                .addProperty("application_name", "peegeeq-management-" + definition.setupId())
                .setSslOptions(new ClientSSLOptions()
                        .setHostnameVerificationAlgorithm("HTTPS")
                        .setTrustOptions(new PemTrustOptions().addCertValue(certificate)));
        Pool pool = Pool.pool(runtime, connectOptions, new PoolOptions()
                .setMaxSize(definition.poolMaxSize())
                .setConnectionTimeout((int) connectTimeout.toMillis())
                .setConnectionTimeoutUnit(TimeUnit.MILLISECONDS)
                .setName("peegeeq-management-" + definition.setupId()));
        SetupRuntimeConfiguration runtimeConfiguration = definition.runtimeConfiguration();
        PeeGeeCacheBootstrapOptions bootstrap = new PeeGeeCacheBootstrapOptions(
                runtimeConfiguration.runtimeConfig(),
                new PgCacheStoreConfig(
                        definition.schema(), runtimeConfiguration.pubSubChannelPrefix()),
                runtimeConfiguration.pubSubEnabled() ? connectOptions : null,
                CacheTelemetry.noop(),
                runtimeConfiguration.schemaBootstrapMode());

        return PeeGeeCaches.create(runtime, pool, bootstrap)
                .map(manager -> (ManagedSetupRuntime) new PostgresManagedSetupRuntime(
                        manager,
                        pool,
                        runtime,
                        definition.schema(),
                        runtimeConfiguration.schemaBootstrapMode(),
                        definition.setupId(),
                        cursorKey,
                        auditSink,
                        auditFingerprinter,
                        auditClock,
                        auditEventIdSupplier))
                .recover(failure -> pool.close()
                        .recover(ignored -> Future.succeededFuture())
                        .compose(ignored -> runtime.close().recover(closeFailure -> Future.succeededFuture()))
                        .compose(ignored -> Future.failedFuture(failure)));
    }

    private static Vertx createPinnedVertx(InetAddress address, String hostname) {
        String hostsEntry = address.getHostAddress() + " " + hostname + System.lineSeparator();
        AddressResolverOptions resolverOptions = new AddressResolverOptions()
                .setHostsValue(Buffer.buffer(hostsEntry))
                .setSearchDomains(List.of());
        return Vertx.vertx(new VertxOptions()
                .setEventLoopPoolSize(1)
                .setWorkerPoolSize(1)
                .setAddressResolverOptions(resolverOptions));
    }

    private static String decodeSecret(byte[] secret) {
        if (secret.length == 0) {
            throw new IllegalArgumentException("Database password must not be empty");
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(secret))
                    .toString();
        } catch (CharacterCodingException failure) {
            throw new IllegalArgumentException("Database password is not valid UTF-8", failure);
        }
    }

    private static byte[] cursorKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return key;
    }
}
