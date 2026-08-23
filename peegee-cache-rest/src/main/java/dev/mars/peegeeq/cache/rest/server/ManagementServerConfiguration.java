package dev.mars.peegeeq.cache.rest.server;

import dev.mars.peegeeq.cache.api.management.ManagementLimits;
import dev.mars.peegeeq.cache.api.management.ManagementSecretReference;
import dev.mars.peegeeq.cache.rest.security.BrowserOriginPolicy;
import dev.mars.peegeeq.cache.rest.security.LocalTokenAuthenticationConfig;
import dev.mars.peegeeq.cache.rest.security.ManagementAuthenticationMode;
import dev.mars.peegeeq.cache.rest.security.SetupTargetPolicy;
import dev.mars.peegeeq.cache.rest.security.TrustedProxyAuthenticationConfig;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Path;
import java.util.Objects;

/** Validated immutable management-server configuration. */
public final class ManagementServerConfiguration {

    private final String bindAddress;
    private final int port;
    private final ManagementAuthenticationMode authenticationMode;
    private final LocalTokenAuthenticationConfig localToken;
    private final TrustedProxyAuthenticationConfig trustedProxy;
    private final BrowserOriginPolicy originPolicy;
    private final SetupTargetPolicy targetPolicy;
    private final Path auditJournal;
    private final ManagementSecretReference auditFingerprintSecret;
    private final ManagementLimits limits;
    private final int maximumRequestBytes;

    public ManagementServerConfiguration(
            String bindAddress,
            int port,
            ManagementAuthenticationMode authenticationMode,
            LocalTokenAuthenticationConfig localToken,
            TrustedProxyAuthenticationConfig trustedProxy,
            BrowserOriginPolicy originPolicy,
            SetupTargetPolicy targetPolicy,
            Path auditJournal,
            ManagementSecretReference auditFingerprintSecret,
            ManagementLimits limits,
            int maximumRequestBytes) {
        this.bindAddress = requireLiteralAddress(bindAddress);
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("Management server port must be between 1 and 65535");
        }
        this.port = port;
        this.authenticationMode = Objects.requireNonNull(authenticationMode, "authenticationMode");
        this.localToken = localToken;
        this.trustedProxy = trustedProxy;
        this.originPolicy = Objects.requireNonNull(originPolicy, "originPolicy");
        this.targetPolicy = Objects.requireNonNull(targetPolicy, "targetPolicy");
        this.auditJournal = Objects.requireNonNull(auditJournal, "auditJournal");
        this.auditFingerprintSecret = Objects.requireNonNull(
                auditFingerprintSecret, "auditFingerprintSecret");
        this.limits = Objects.requireNonNull(limits, "limits");
        if (auditJournal.toString().isBlank()) {
            throw new IllegalArgumentException("Audit journal path is required");
        }
        if (maximumRequestBytes < 1) {
            throw new IllegalArgumentException("maximumRequestBytes must be positive");
        }
        this.maximumRequestBytes = maximumRequestBytes;
        validateAuthenticationMode();
    }

    public static ManagementServerConfiguration localToken(
            String bindAddress,
            int port,
            String serverOrigin,
            SetupTargetPolicy targetPolicy,
            Path auditJournal,
            ManagementSecretReference auditFingerprintSecret) {
        return new ManagementServerConfiguration(
                bindAddress,
                port,
                ManagementAuthenticationMode.LOCAL_TOKEN,
                LocalTokenAuthenticationConfig.defaults(),
                null,
                BrowserOriginPolicy.localToken(serverOrigin),
                targetPolicy,
                auditJournal,
                auditFingerprintSecret,
                ManagementLimits.defaults(),
                10 * 1024 * 1024);
    }

    public static ManagementServerConfiguration trustedProxy(
            String bindAddress,
            int port,
            TrustedProxyAuthenticationConfig trustedProxy,
            BrowserOriginPolicy originPolicy,
            SetupTargetPolicy targetPolicy,
            Path auditJournal,
            ManagementSecretReference auditFingerprintSecret) {
        return new ManagementServerConfiguration(
                bindAddress,
                port,
                ManagementAuthenticationMode.TRUSTED_PROXY,
                null,
                trustedProxy,
                originPolicy,
                targetPolicy,
                auditJournal,
                auditFingerprintSecret,
                ManagementLimits.defaults(),
                10 * 1024 * 1024);
    }

    public String bindAddress() {
        return bindAddress;
    }

    public int port() {
        return port;
    }

    public ManagementAuthenticationMode authenticationMode() {
        return authenticationMode;
    }

    public LocalTokenAuthenticationConfig localToken() {
        return localToken;
    }

    public TrustedProxyAuthenticationConfig trustedProxy() {
        return trustedProxy;
    }

    public BrowserOriginPolicy originPolicy() {
        return originPolicy;
    }

    public SetupTargetPolicy targetPolicy() {
        return targetPolicy;
    }

    public Path auditJournal() {
        return auditJournal;
    }

    public ManagementSecretReference auditFingerprintSecret() {
        return auditFingerprintSecret;
    }

    public ManagementLimits limits() {
        return limits;
    }

    public int maximumRequestBytes() {
        return maximumRequestBytes;
    }

    @Override
    public String toString() {
        return "ManagementServerConfiguration[bindAddress=" + bindAddress
                + ", port=" + port
                + ", authenticationMode=" + authenticationMode
                + ", auditJournal=" + auditJournal
                + ", auditFingerprintSecret=<reference-redacted>"
                + ", limits=" + limits
                + ", maximumRequestBytes=" + maximumRequestBytes + ']';
    }

    private void validateAuthenticationMode() {
        boolean valid = switch (authenticationMode) {
            case LOCAL_TOKEN -> localToken != null
                    && trustedProxy == null
                    && originPolicy.mode() == ManagementAuthenticationMode.LOCAL_TOKEN
                    && isLoopback(bindAddress);
            case TRUSTED_PROXY -> localToken == null
                    && trustedProxy != null
                    && originPolicy.mode() == ManagementAuthenticationMode.TRUSTED_PROXY;
        };
        if (!valid) {
            throw new IllegalArgumentException("Exactly one compatible authentication mode must be configured");
        }
    }

    private static String requireLiteralAddress(String value) {
        String address = Objects.requireNonNull(value, "bindAddress").trim();
        if (address.isEmpty()
                || !(address.matches("[0-9.]+") || address.matches("[0-9A-Fa-f:]+"))) {
            throw new IllegalArgumentException("Bind address must be an IP literal");
        }
        try {
            InetAddress.getByName(address);
        } catch (UnknownHostException exception) {
            throw new IllegalArgumentException("Invalid bind address", exception);
        }
        return address;
    }

    private static boolean isLoopback(String address) {
        try {
            return InetAddress.getByName(address).isLoopbackAddress();
        } catch (UnknownHostException exception) {
            return false;
        }
    }
}
