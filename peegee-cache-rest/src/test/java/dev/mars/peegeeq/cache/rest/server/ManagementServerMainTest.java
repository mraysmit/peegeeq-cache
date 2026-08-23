package dev.mars.peegeeq.cache.rest.server;

import dev.mars.peegeeq.cache.api.management.ManagementSecretReference;
import dev.mars.peegeeq.cache.rest.security.ManagementAuthenticationMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ManagementServerMainTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void createsSafeDocumentedLocalDefaultsFromEnvironment() throws Exception {
        Path trustPem = temporaryDirectory.resolve("database-ca.pem");
        Files.writeString(trustPem, "test-certificate", StandardCharsets.UTF_8);
        String auditKey = "0123456789abcdef0123456789abcdef";

        ManagementServerMain.LaunchConfiguration launch = ManagementServerMain.fromEnvironment(
                Map.of(
                        "PEEGEEQ_MANAGEMENT_AUDIT_KEY", auditKey,
                        "PEEGEEQ_MANAGEMENT_TRUST_PEM", trustPem.toString()));

        assertEquals("127.0.0.1", launch.server().bindAddress());
        assertEquals(8080, launch.server().port());
        assertEquals("http://127.0.0.1:8080", launch.server().originPolicy().serverOrigin());
        assertEquals(Path.of("logs", "management-audit.jsonl"), launch.server().auditJournal());
        assertArrayEquals(
                auditKey.getBytes(StandardCharsets.UTF_8),
                launch.secrets().resolve(new ManagementSecretReference(
                        "env:PEEGEEQ_MANAGEMENT_AUDIT_KEY")));
        assertEquals("test-certificate", launch.trustProfiles().resolve("default").toString());
        launch.server().targetPolicy().validate(
                "orders.internal", 5432,
                java.util.List.of(InetAddress.getByName("10.1.2.3")), "default");
    }

    @Test
    void failsClosedWhenRequiredAuditKeyIsMissingOrTooShort() {
        assertThrows(IllegalArgumentException.class,
                () -> ManagementServerMain.fromEnvironment(Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> ManagementServerMain.fromEnvironment(Map.of(
                        "PEEGEEQ_MANAGEMENT_AUDIT_KEY", "too-short")));
    }

    @Test
    void createsTrustedProxyConfigurationOnlyFromExplicitNetworkAndOriginPolicy() {
        ManagementServerMain.LaunchConfiguration launch = ManagementServerMain.fromEnvironment(
                Map.of(
                        "PEEGEEQ_MANAGEMENT_AUDIT_KEY", "0123456789abcdef0123456789abcdef",
                        "PEEGEEQ_MANAGEMENT_AUTHENTICATION_MODE", "TRUSTED_PROXY",
                        "PEEGEEQ_MANAGEMENT_SERVER_ORIGIN", "https://management.internal.example",
                        "PEEGEEQ_MANAGEMENT_TRUSTED_PROXY_CIDRS", "127.0.0.0/8,10.40.0.0/16",
                        "PEEGEEQ_MANAGEMENT_ALLOWED_ORIGINS", "https://console.example.com"));

        assertEquals(ManagementAuthenticationMode.TRUSTED_PROXY,
                launch.server().authenticationMode());
        assertEquals(Set.of("127.0.0.0/8", "10.40.0.0/16"),
                launch.server().trustedProxy().trustedProxyCidrs());
        assertEquals(Set.of("viewer", "operator"),
                launch.server().trustedProxy().allowedRoles());
        assertEquals("X-PeeGeeQ-User", launch.server().trustedProxy().userHeaderName());
        assertEquals("X-PeeGeeQ-Roles", launch.server().trustedProxy().rolesHeaderName());
        assertEquals("https://console.example.com",
                launch.server().originPolicy().allowCredentialedOrigin(
                        "https://console.example.com").orElseThrow());
    }

    @Test
    void trustedProxyModeFailsClosedWithoutExplicitCidrsAndHttpsOrigins() {
        Map<String, String> base = Map.of(
                "PEEGEEQ_MANAGEMENT_AUDIT_KEY", "0123456789abcdef0123456789abcdef",
                "PEEGEEQ_MANAGEMENT_AUTHENTICATION_MODE", "TRUSTED_PROXY",
                "PEEGEEQ_MANAGEMENT_SERVER_ORIGIN", "https://management.internal.example");

        assertThrows(IllegalArgumentException.class,
                () -> ManagementServerMain.fromEnvironment(base));
    }
}
