package dev.mars.peegeeq.cache.rest.server;

import dev.mars.peegeeq.cache.api.management.ManagementLimits;
import dev.mars.peegeeq.cache.api.management.ManagementSecretReference;
import dev.mars.peegeeq.cache.rest.security.BrowserOriginPolicy;
import dev.mars.peegeeq.cache.rest.security.LocalTokenAuthenticationConfig;
import dev.mars.peegeeq.cache.rest.security.ManagementAuthenticationMode;
import dev.mars.peegeeq.cache.rest.security.SetupTargetPolicy;
import dev.mars.peegeeq.cache.rest.security.TrustedProxyAuthenticationConfig;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ManagementServerConfigurationTest {

    private final SetupTargetPolicy targetPolicy = SetupTargetPolicy.privateNetworks(
            Set.of("internal.example"), Set.of("10.0.0.0/8"), Set.of(5432), Set.of("corp-ca"));

    @Test
    void acceptsSafeLocalAndTrustedProxyConfigurations() {
        ManagementServerConfiguration local = ManagementServerConfiguration.localToken(
                "127.0.0.1", 9080, "http://127.0.0.1:9080",
                targetPolicy, Path.of("logs", "management-audit.jsonl"),
                new ManagementSecretReference("env:PGQ_AUDIT_KEY"));
        ManagementServerConfiguration trusted = ManagementServerConfiguration.trustedProxy(
                "0.0.0.0", 9443,
                TrustedProxyAuthenticationConfig.defaults(Set.of("10.24.0.0/16")),
                BrowserOriginPolicy.trustedProxy(
                        "https://admin.internal.example", Set.of("https://console.example.com")),
                targetPolicy, Path.of("logs", "management-audit.jsonl"),
                new ManagementSecretReference("env:PGQ_AUDIT_KEY"));

        assertEquals(ManagementAuthenticationMode.LOCAL_TOKEN, local.authenticationMode());
        assertEquals(ManagementAuthenticationMode.TRUSTED_PROXY, trusted.authenticationMode());
        assertEquals(ManagementLimits.defaults(), local.limits());
        assertFalse(local.toString().contains("env:PGQ_AUDIT_KEY"));
    }

    @Test
    void rejectsUnsafeBindContradictoryModesAndMissingSecurityDependencies() {
        assertThrows(IllegalArgumentException.class, () -> ManagementServerConfiguration.localToken(
                "0.0.0.0", 9080, "http://127.0.0.1:9080",
                targetPolicy, Path.of("audit.jsonl"), new ManagementSecretReference("secret")));
        assertThrows(IllegalArgumentException.class, () -> new ManagementServerConfiguration(
                "127.0.0.1", 9080, ManagementAuthenticationMode.LOCAL_TOKEN,
                LocalTokenAuthenticationConfig.defaults(),
                TrustedProxyAuthenticationConfig.defaults(Set.of("127.0.0.1/32")),
                BrowserOriginPolicy.localToken("http://127.0.0.1:9080"),
                targetPolicy, Path.of("audit.jsonl"), new ManagementSecretReference("secret"),
                ManagementLimits.defaults(), 1024 * 1024));
        assertThrows(NullPointerException.class, () -> ManagementServerConfiguration.localToken(
                "127.0.0.1", 9080, "http://127.0.0.1:9080",
                null, Path.of("audit.jsonl"), new ManagementSecretReference("secret")));
        assertThrows(IllegalArgumentException.class, () -> ManagementServerConfiguration.localToken(
                "127.0.0.1", 0, "http://127.0.0.1:9080",
                targetPolicy, Path.of("audit.jsonl"), new ManagementSecretReference("secret")));
    }

    @Test
    void rejectsUnsafeLifetimesLimitsJournalAndFingerprintReference() {
        assertThrows(IllegalArgumentException.class, () -> new LocalTokenAuthenticationConfig(
                ManagementAuthenticationMode.LOCAL_TOKEN, Duration.ofHours(9), Duration.ofHours(8)));
        assertThrows(IllegalArgumentException.class, () -> new ManagementServerConfiguration(
                "127.0.0.1", 9080, ManagementAuthenticationMode.LOCAL_TOKEN,
                LocalTokenAuthenticationConfig.defaults(), null,
                BrowserOriginPolicy.localToken("http://127.0.0.1:9080"), targetPolicy,
                Path.of(""), new ManagementSecretReference("secret"),
                ManagementLimits.defaults(), 1024));
        assertThrows(IllegalArgumentException.class, () -> new ManagementServerConfiguration(
                "127.0.0.1", 9080, ManagementAuthenticationMode.LOCAL_TOKEN,
                LocalTokenAuthenticationConfig.defaults(), null,
                BrowserOriginPolicy.localToken("http://127.0.0.1:9080"), targetPolicy,
                Path.of("audit.jsonl"), new ManagementSecretReference("secret"),
                ManagementLimits.defaults(), 0));
    }
}
