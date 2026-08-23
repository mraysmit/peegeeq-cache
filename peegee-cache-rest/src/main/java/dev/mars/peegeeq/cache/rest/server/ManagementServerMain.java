package dev.mars.peegeeq.cache.rest.server;

import dev.mars.peegeeq.cache.api.management.ManagementSecretProvider;
import dev.mars.peegeeq.cache.api.management.ManagementSecretReference;
import dev.mars.peegeeq.cache.rest.security.BrowserOriginPolicy;
import dev.mars.peegeeq.cache.rest.security.ManagementAuthenticationMode;
import dev.mars.peegeeq.cache.rest.security.SetupTargetPolicy;
import dev.mars.peegeeq.cache.rest.security.TargetAddressResolver;
import dev.mars.peegeeq.cache.rest.security.TrustProfileCertificateResolver;
import dev.mars.peegeeq.cache.rest.security.TrustedProxyAuthenticationConfig;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import io.vertx.core.buffer.Buffer;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/** Environment-configured executable entry point for the management server. */
public final class ManagementServerMain {

    private static final String PREFIX = "PEEGEEQ_MANAGEMENT_";
    private static final String AUDIT_KEY = PREFIX + "AUDIT_KEY";
    private static final String AUDIT_KEY_REFERENCE = "env:" + AUDIT_KEY;
    private static final Pattern ENVIRONMENT_NAME = Pattern.compile("[A-Z][A-Z0-9_]{0,127}");

    private ManagementServerMain() {
    }

    public static void main(String[] args) throws Exception {
        LaunchConfiguration launch = fromEnvironment(System.getenv());
        PrometheusMeterRegistry meters = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        ManagementServerApplication application = ManagementServerApplication.start(
                        launch.server(),
                        launch.secrets(),
                        launch.targetResolver(),
                        launch.trustProfiles(),
                        meters)
                .toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
        Runtime.getRuntime().addShutdownHook(Thread.ofPlatform()
                .name("peegeeq-management-shutdown")
                .unstarted(() -> {
                    try {
                        application.closeAsync().toCompletionStage().toCompletableFuture()
                                .get(30, TimeUnit.SECONDS);
                    } catch (Exception failure) {
                        System.err.println("PeeGeeQ management shutdown did not complete cleanly");
                    } finally {
                        meters.close();
                    }
                }));
        application.takeBootstrapToken().ifPresent(token ->
                System.out.println("PEEGEEQ_MANAGEMENT_BOOTSTRAP_TOKEN=" + token));
        new CountDownLatch(1).await();
    }

    static LaunchConfiguration fromEnvironment(Map<String, String> environment) {
        Objects.requireNonNull(environment, "environment");
        String auditKey = required(environment, AUDIT_KEY);
        if (auditKey.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalArgumentException(
                    AUDIT_KEY + " must contain at least 32 UTF-8 bytes");
        }

        String bindAddress = value(environment, PREFIX + "BIND_ADDRESS", "127.0.0.1");
        int port = integer(environment, PREFIX + "PORT", 8080);
        ManagementAuthenticationMode authenticationMode = authenticationMode(environment);
        String serverOrigin = authenticationMode == ManagementAuthenticationMode.LOCAL_TOKEN
                ? value(environment, PREFIX + "SERVER_ORIGIN", "http://" + bindAddress + ':' + port)
                : required(environment, PREFIX + "SERVER_ORIGIN").trim();
        String trustProfile = value(environment, PREFIX + "TRUST_PROFILE_ID", "default");
        Set<String> addressClasses = csv(
                value(environment, PREFIX + "TARGET_ADDRESS_CLASSES", "PRIVATE"));
        SetupTargetPolicy targetPolicy = new SetupTargetPolicy(
                csv(value(environment, PREFIX + "TARGET_DNS_SUFFIXES", "internal")),
                csv(value(environment, PREFIX + "TARGET_CIDRS", "10.0.0.0/8")),
                integerCsv(value(environment, PREFIX + "TARGET_PORTS", "5432")),
                addressClasses.contains("LOOPBACK"),
                addressClasses.contains("PRIVATE"),
                addressClasses.contains("LINK_LOCAL"),
                addressClasses.contains("PUBLIC"),
                Set.of(trustProfile));
        Path auditJournal = Path.of(value(
                environment,
                PREFIX + "AUDIT_JOURNAL",
                Path.of("logs", "management-audit.jsonl").toString()));
        ManagementSecretReference auditKeyReference =
                new ManagementSecretReference(AUDIT_KEY_REFERENCE);
        ManagementServerConfiguration server;
        if (authenticationMode == ManagementAuthenticationMode.LOCAL_TOKEN) {
            server = ManagementServerConfiguration.localToken(
                    bindAddress, port, serverOrigin, targetPolicy, auditJournal, auditKeyReference);
        } else {
            TrustedProxyAuthenticationConfig proxy = new TrustedProxyAuthenticationConfig(
                    csv(required(environment, PREFIX + "TRUSTED_PROXY_CIDRS")),
                    value(environment, PREFIX + "TRUSTED_PROXY_USER_HEADER",
                            TrustedProxyAuthenticationConfig.DEFAULT_USER_HEADER),
                    value(environment, PREFIX + "TRUSTED_PROXY_ROLES_HEADER",
                            TrustedProxyAuthenticationConfig.DEFAULT_ROLES_HEADER),
                    csv(value(environment, PREFIX + "TRUSTED_PROXY_ALLOWED_ROLES",
                            "viewer,operator")));
            server = ManagementServerConfiguration.trustedProxy(
                    bindAddress,
                    port,
                    proxy,
                    BrowserOriginPolicy.trustedProxy(
                            serverOrigin,
                            csv(required(environment, PREFIX + "ALLOWED_ORIGINS"))),
                    targetPolicy,
                    auditJournal,
                    auditKeyReference);
        }
        ManagementSecretProvider secrets = reference -> resolveSecret(environment, reference);
        TargetAddressResolver resolver = hostname ->
                Arrays.asList(InetAddress.getAllByName(hostname));
        TrustProfileCertificateResolver trustProfiles = profile -> {
            if (!trustProfile.equals(profile)) {
                throw new IllegalArgumentException("Unknown management trust profile");
            }
            String pem = required(environment, PREFIX + "TRUST_PEM");
            try {
                return Buffer.buffer(Files.readAllBytes(Path.of(pem)));
            } catch (java.io.IOException failure) {
                throw new IllegalArgumentException("Management trust PEM could not be read", failure);
            }
        };
        return new LaunchConfiguration(server, secrets, resolver, trustProfiles);
    }

    private static byte[] resolveSecret(
            Map<String, String> environment, ManagementSecretReference reference) {
        String value = reference.reference();
        if (!value.startsWith("env:")) {
            throw new IllegalArgumentException("Only env: management secret references are supported");
        }
        String name = value.substring("env:".length());
        if (!ENVIRONMENT_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Management environment secret reference is invalid");
        }
        return required(environment, name).getBytes(StandardCharsets.UTF_8);
    }

    private static ManagementAuthenticationMode authenticationMode(
            Map<String, String> environment) {
        String value = value(environment, PREFIX + "AUTHENTICATION_MODE", "LOCAL_TOKEN");
        try {
            return ManagementAuthenticationMode.valueOf(value);
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException(
                    PREFIX + "AUTHENTICATION_MODE must be LOCAL_TOKEN or TRUSTED_PROXY", failure);
        }
    }

    private static String required(Map<String, String> environment, String name) {
        String value = environment.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }

    private static String value(
            Map<String, String> environment, String name, String defaultValue) {
        String value = environment.get(name);
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }

    private static int integer(
            Map<String, String> environment, String name, int defaultValue) {
        String value = environment.get(name);
        if (value == null || value.isBlank()) return defaultValue;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException(name + " must be an integer", failure);
        }
    }

    private static Set<String> csv(String value) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        for (String item : value.split(",", -1)) {
            String normalized = item.trim();
            if (normalized.isEmpty() || !values.add(normalized)) {
                throw new IllegalArgumentException("Management CSV configuration is invalid");
            }
        }
        return Set.copyOf(values);
    }

    private static Set<Integer> integerCsv(String value) {
        LinkedHashSet<Integer> values = new LinkedHashSet<>();
        for (String item : csv(value)) {
            try {
                values.add(Integer.valueOf(item));
            } catch (NumberFormatException failure) {
                throw new IllegalArgumentException(
                        "Management target ports must be integers", failure);
            }
        }
        return Set.copyOf(values);
    }

    record LaunchConfiguration(
            ManagementServerConfiguration server,
            ManagementSecretProvider secrets,
            TargetAddressResolver targetResolver,
            TrustProfileCertificateResolver trustProfiles) {
        LaunchConfiguration {
            Objects.requireNonNull(server, "server");
            Objects.requireNonNull(secrets, "secrets");
            Objects.requireNonNull(targetResolver, "targetResolver");
            Objects.requireNonNull(trustProfiles, "trustProfiles");
        }
    }
}
