package dev.mars.peegeeq.cache.rest.security;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.dns.AddressResolverOptions;
import io.vertx.core.net.ClientSSLOptions;
import io.vertx.core.net.PemTrustOptions;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.pgclient.PgConnection;
import io.vertx.pgclient.SslMode;

import java.net.InetAddress;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Performs a PostgreSQL readiness connection through an isolated, pinned Vert.x resolver.
 * The socket resolver sees only the validated address while TLS continues to see the
 * configured hostname for SNI and endpoint identification.
 */
public final class VertxPinnedDatabaseConnector implements PinnedDatabaseConnector, AutoCloseable {

    private final String database;
    private final String username;
    private final char[] password;
    private final TrustProfileCertificateResolver trustProfiles;
    private final Duration connectTimeout;
    private final Set<Vertx> activeRuntimes = new HashSet<>();
    private boolean closed;
    private volatile InetAddress lastPinnedAddress;
    private volatile String lastTlsServerName;

    public VertxPinnedDatabaseConnector(
            String database,
            String username,
            char[] password,
            TrustProfileCertificateResolver trustProfiles,
            Duration connectTimeout) {
        this.database = requireText(database, "database");
        this.username = requireText(username, "username");
        this.password = Objects.requireNonNull(password, "password").clone();
        this.trustProfiles = Objects.requireNonNull(trustProfiles, "trustProfiles");
        this.connectTimeout = Objects.requireNonNull(connectTimeout, "connectTimeout");
        if (connectTimeout.isZero() || connectTimeout.isNegative()) {
            throw new IllegalArgumentException("connectTimeout must be positive");
        }
    }

    @Override
    public Future<Void> connect(
            InetAddress address,
            int port,
            String tlsServerName,
            String trustProfileId,
            TlsMode tlsMode) {
        Objects.requireNonNull(address, "address");
        String serverName = requireText(tlsServerName, "tlsServerName");
        if (port < 1 || port > 65_535) {
            throw new IllegalArgumentException("port must be between 1 and 65535");
        }
        if (tlsMode != TlsMode.VERIFY_FULL) {
            throw new IllegalArgumentException("Pinned database connections require VERIFY_FULL TLS");
        }

        Buffer certificate = Objects.requireNonNull(
                trustProfiles.resolve(requireText(trustProfileId, "trustProfileId")),
                "Unknown trust profile: " + trustProfileId);
        Vertx runtime = createPinnedRuntime(address, serverName);
        synchronized (this) {
            if (closed) {
                runtime.close();
                return Future.failedFuture(new IllegalStateException("Connector is closed"));
            }
            activeRuntimes.add(runtime);
        }

        lastPinnedAddress = address;
        lastTlsServerName = serverName;
        PgConnectOptions options = new PgConnectOptions()
                .setHost(serverName)
                .setPort(port)
                .setDatabase(database)
                .setUser(username)
                .setPassword(new String(password))
                .setSslMode(SslMode.VERIFY_FULL)
                .setReconnectAttempts(0)
                .setSslOptions(new ClientSSLOptions()
                        .setHostnameVerificationAlgorithm("HTTPS")
                        .setTrustOptions(new PemTrustOptions().addCertValue(certificate)));

        Future<Void> connection = PgConnection.connect(runtime, options)
                .timeout(connectTimeout.toMillis(), TimeUnit.MILLISECONDS)
                .compose(VertxPinnedDatabaseConnector::verifyAndClose);
        return finishAfterRuntimeClose(connection, runtime);
    }

    InetAddress lastPinnedAddress() {
        return lastPinnedAddress;
    }

    String lastTlsServerName() {
        return lastTlsServerName;
    }

    synchronized int activeRuntimeCount() {
        return activeRuntimes.size();
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        Arrays.fill(password, '\0');
        for (Vertx runtime : Set.copyOf(activeRuntimes)) {
            runtime.close();
        }
        activeRuntimes.clear();
    }

    private static Vertx createPinnedRuntime(InetAddress address, String tlsServerName) {
        String hostsEntry = address.getHostAddress() + " " + tlsServerName + System.lineSeparator();
        AddressResolverOptions resolver = new AddressResolverOptions()
                .setHostsValue(Buffer.buffer(hostsEntry))
                .setSearchDomains(java.util.List.of());
        return Vertx.vertx(new VertxOptions()
                .setEventLoopPoolSize(1)
                .setWorkerPoolSize(1)
                .setAddressResolverOptions(resolver));
    }

    private static Future<Void> verifyAndClose(PgConnection connection) {
        Future<Void> verification = connection.isSSL()
                ? connection.query("SELECT 1").execute().mapEmpty()
                : Future.failedFuture(new IllegalStateException("PostgreSQL connection did not negotiate TLS"));
        return verification.eventually(connection::close);
    }

    private Future<Void> finishAfterRuntimeClose(Future<Void> operation, Vertx runtime) {
        Promise<Void> completion = Promise.promise();
        operation.onComplete(result -> Thread.startVirtualThread(() ->
                runtime.close().onComplete(closeResult -> {
                    synchronized (this) {
                        activeRuntimes.remove(runtime);
                    }
                    if (result.failed()) {
                        completion.fail(result.cause());
                    } else if (closeResult.failed()) {
                        completion.fail(closeResult.cause());
                    } else {
                        completion.complete();
                    }
                })));
        return completion.future();
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
