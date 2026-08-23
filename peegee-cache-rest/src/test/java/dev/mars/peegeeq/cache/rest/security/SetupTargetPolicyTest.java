package dev.mars.peegeeq.cache.rest.security;

import io.vertx.core.Future;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SetupTargetPolicyTest {

    @Test
    void permitsOnlyConfiguredHostPortAddressClassAndCidr() throws Exception {
        SetupTargetPolicy policy = new SetupTargetPolicy(
                Set.of("db.internal.example"),
                Set.of("10.40.0.0/16"),
                Set.of(5432),
                false, true, false, false,
                Set.of("corp-ca"));

        policy.validate("orders.db.internal.example", 5432,
                List.of(InetAddress.getByName("10.40.7.9")), "corp-ca");

        assertTargetForbidden(() -> policy.validate("orders.db.internal.example", 6432,
                List.of(InetAddress.getByName("10.40.7.9")), "corp-ca"));
        assertTargetForbidden(() -> policy.validate("orders.evil.example", 5432,
                List.of(InetAddress.getByName("10.40.7.9")), "corp-ca"));
        assertTargetForbidden(() -> policy.validate("orders.db.internal.example", 5432,
                List.of(InetAddress.getByName("10.41.7.9")), "corp-ca"));
        assertTargetForbidden(() -> policy.validate("orders.db.internal.example", 5432,
                List.of(InetAddress.getByName("10.40.7.9")), "request-path"));
    }

    @Test
    void blocksLoopbackLinkLocalCloudMetadataAndPublicByDefault() throws Exception {
        SetupTargetPolicy policy = SetupTargetPolicy.privateNetworks(
                Set.of("internal.example"), Set.of("10.0.0.0/8"), Set.of(5432), Set.of("system"));

        assertTargetForbidden(() -> policy.validate("db.internal.example", 5432,
                List.of(InetAddress.getByName("127.0.0.1")), "system"));
        assertTargetForbidden(() -> policy.validate("db.internal.example", 5432,
                List.of(InetAddress.getByName("169.254.169.254")), "system"));
        assertTargetForbidden(() -> policy.validate("db.internal.example", 5432,
                List.of(InetAddress.getByName("8.8.8.8")), "system"));
    }

    @Test
    void rejectsWholeResolutionWhenAnyAnswerIsForbidden() throws Exception {
        SetupTargetPolicy policy = SetupTargetPolicy.privateNetworks(
                Set.of("internal.example"), Set.of("10.0.0.0/8"), Set.of(5432), Set.of("system"));

        assertTargetForbidden(() -> policy.validate(
                "db.internal.example",
                5432,
                List.of(InetAddress.getByName("10.2.3.4"), InetAddress.getByName("8.8.8.8")),
                "system"));
    }

    @Test
    void pinsValidatedAddressWhilePreservingHostnameForTlsVerification() throws Exception {
        QueueResolver resolver = new QueueResolver(
                List.of(InetAddress.getByName("10.30.1.9")),
                List.of(InetAddress.getByName("169.254.169.254")));
        RecordingConnector connector = new RecordingConnector();
        SetupTargetPolicy policy = SetupTargetPolicy.privateNetworks(
                Set.of("internal.example"), Set.of("10.0.0.0/8"), Set.of(5432), Set.of("corp-ca"));
        PolicyAwareTargetConnector targetConnector = new PolicyAwareTargetConnector(policy, resolver, connector);

        targetConnector.connect(new SetupTarget("orders.internal.example", 5432, "corp-ca", TlsMode.VERIFY_FULL));

        assertEquals("10.30.1.9", connector.address.getHostAddress());
        assertEquals("orders.internal.example", connector.tlsServerName);
        assertEquals("corp-ca", connector.trustProfileId);
        assertEquals(TlsMode.VERIFY_FULL, connector.tlsMode);
        assertTargetForbidden(() -> targetConnector.connect(
                new SetupTarget("orders.internal.example", 5432, "corp-ca", TlsMode.VERIFY_FULL)));
        assertEquals(1, connector.calls);
    }

    @Test
    void rejectsEmptyAnswersUnsafeHostSyntaxAndNonVerifyingTls() throws Exception {
        SetupTargetPolicy policy = SetupTargetPolicy.privateNetworks(
                Set.of("internal.example"), Set.of("10.0.0.0/8"), Set.of(5432), Set.of("corp-ca"));

        assertTargetForbidden(() -> policy.validate("db.internal.example", 5432, List.of(), "corp-ca"));
        assertTargetForbidden(() -> policy.validate("db.internal.example.", 5432,
                List.of(InetAddress.getByName("10.1.1.1")), "corp-ca"));
        assertThrows(IllegalArgumentException.class,
                () -> new SetupTarget("db.internal.example", 5432, "corp-ca", TlsMode.DISABLE));
    }

    private static void assertTargetForbidden(ThrowingRunnable runnable) {
        ManagementSecurityException failure = assertThrows(ManagementSecurityException.class, runnable::run);
        assertEquals("TARGET_FORBIDDEN", failure.code());
        assertTrue(failure.getMessage().startsWith("Database target"));
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static final class QueueResolver implements TargetAddressResolver {
        private final ArrayDeque<List<InetAddress>> answers;

        @SafeVarargs
        private QueueResolver(List<InetAddress>... answers) {
            this.answers = new ArrayDeque<>(List.of(answers));
        }

        @Override
        public List<InetAddress> resolve(String hostname) {
            return answers.removeFirst();
        }
    }

    private static final class RecordingConnector implements PinnedDatabaseConnector {
        private int calls;
        private InetAddress address;
        private String tlsServerName;
        private String trustProfileId;
        private TlsMode tlsMode;

        @Override
        public Future<Void> connect(
                InetAddress address,
                int port,
                String tlsServerName,
                String trustProfileId,
                TlsMode tlsMode) {
            calls++;
            this.address = address;
            this.tlsServerName = tlsServerName;
            this.trustProfileId = trustProfileId;
            this.tlsMode = tlsMode;
            return Future.succeededFuture();
        }
    }
}
