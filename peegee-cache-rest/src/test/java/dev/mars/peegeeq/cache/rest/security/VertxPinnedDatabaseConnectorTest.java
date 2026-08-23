package dev.mars.peegeeq.cache.rest.security;

import dev.mars.peegeeq.cache.test.PostgreSQLTestConstants;
import io.vertx.core.buffer.Buffer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

import java.net.InetAddress;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class VertxPinnedDatabaseConnectorTest {

    private static final String TLS_HOSTNAME = "db.internal.example";
    private static PostgreSQLContainer postgres;
    private static Buffer serverCertificate;

    @BeforeAll
    static void startPostgresWithTls() throws Exception {
        postgres = new PostgreSQLContainer(PostgreSQLTestConstants.postgresImage())
                .withDatabaseName("peegeeq")
                .withUsername("peegeeq")
                .withPassword("test-password")
                .withCopyFileToContainer(
                        MountableFile.forClasspathResource("postgres-tls-init.sh", 0775),
                        "/docker-entrypoint-initdb.d/010-postgres-tls-init.sh")
                .withCopyFileToContainer(
                        MountableFile.forClasspathResource("postgres-tls-server.crt", 0444),
                        "/docker-entrypoint-initdb.d/server.crt")
                .withCopyFileToContainer(
                        MountableFile.forClasspathResource("postgres-tls-server.key", 0444),
                        "/docker-entrypoint-initdb.d/server.key");
        postgres.start();

        try (var certificate = VertxPinnedDatabaseConnectorTest.class.getResourceAsStream(
                "/postgres-tls-server.crt")) {
            serverCertificate = Buffer.buffer(certificate.readAllBytes());
        }
    }

    @AfterAll
    static void stopPostgres() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    @Test
    void connectsToValidatedAddressWhileVerifyFullUsesConfiguredHostname() throws Exception {
        InetAddress pinnedAddress = InetAddress.getByName(postgres.getHost());
        SetupTargetPolicy policy = new SetupTargetPolicy(
                Set.of("internal.example"), Set.of("127.0.0.0/8"), Set.of(postgres.getMappedPort(5432)),
                true, false, false, false, Set.of("test-ca"));

        try (VertxPinnedDatabaseConnector connector = new VertxPinnedDatabaseConnector(
                "peegeeq", "peegeeq", "test-password".toCharArray(),
                trustProfile -> serverCertificate, Duration.ofSeconds(10))) {
            PolicyAwareTargetConnector policyConnector = new PolicyAwareTargetConnector(
                    policy, ignored -> java.util.List.of(pinnedAddress), connector);

            policyConnector.connect(new SetupTarget(
                            TLS_HOSTNAME, postgres.getMappedPort(5432), "test-ca", TlsMode.VERIFY_FULL))
                    .toCompletionStage().toCompletableFuture().get(15, TimeUnit.SECONDS);

            assertEquals(pinnedAddress, connector.lastPinnedAddress());
            assertEquals(TLS_HOSTNAME, connector.lastTlsServerName());
            assertEquals(0, connector.activeRuntimeCount(),
                    "A completed readiness check must also finish shutting down its isolated Vert.x runtime");
        }
    }

    @Test
    void rejectsCertificateWhoseHostnameDoesNotMatchConfiguredTarget() throws Exception {
        InetAddress pinnedAddress = InetAddress.getByName(postgres.getHost());
        SetupTargetPolicy policy = new SetupTargetPolicy(
                Set.of("internal.example"), Set.of("127.0.0.0/8"), Set.of(postgres.getMappedPort(5432)),
                true, false, false, false, Set.of("test-ca"));

        try (VertxPinnedDatabaseConnector connector = new VertxPinnedDatabaseConnector(
                "peegeeq", "peegeeq", "test-password".toCharArray(),
                trustProfile -> serverCertificate, Duration.ofSeconds(10))) {
            PolicyAwareTargetConnector policyConnector = new PolicyAwareTargetConnector(
                    policy, ignored -> java.util.List.of(pinnedAddress), connector);

            ExecutionException failure = assertThrows(ExecutionException.class, () ->
                    policyConnector.connect(new SetupTarget(
                                    "other.internal.example", postgres.getMappedPort(5432),
                                    "test-ca", TlsMode.VERIFY_FULL))
                            .toCompletionStage().toCompletableFuture().get(15, TimeUnit.SECONDS));

            assertEquals("SSL handshake failed", rootMessage(failure));
            assertEquals(0, connector.activeRuntimeCount(),
                    "A failed readiness check must also finish shutting down its isolated Vert.x runtime");
        }
    }

    private static String rootMessage(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return String.valueOf(current.getMessage());
    }
}
