package dev.mars.peegeeq.cache.rest.security;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Exact-origin policy for credentialed management browser requests. */
public final class BrowserOriginPolicy {

    private final ManagementAuthenticationMode mode;
    private final String serverOrigin;
    private final Set<String> allowedOrigins;

    private BrowserOriginPolicy(
            ManagementAuthenticationMode mode,
            String serverOrigin,
            Set<String> additionalOrigins) {
        this.mode = Objects.requireNonNull(mode, "mode");
        this.serverOrigin = normalizeOrigin(serverOrigin, false);
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        normalized.add(this.serverOrigin);
        for (String origin : Objects.requireNonNull(additionalOrigins, "additionalOrigins")) {
            String value = normalizeOrigin(origin, mode == ManagementAuthenticationMode.TRUSTED_PROXY);
            if (!normalized.add(value)) {
                throw new IllegalArgumentException("Duplicate allowed origin");
            }
        }
        if (mode == ManagementAuthenticationMode.LOCAL_TOKEN && normalized.size() != 1) {
            throw new IllegalArgumentException("LOCAL_TOKEN mode cannot enable CORS");
        }
        this.allowedOrigins = Set.copyOf(normalized);
    }

    public static BrowserOriginPolicy localToken(String serverOrigin) {
        return new BrowserOriginPolicy(ManagementAuthenticationMode.LOCAL_TOKEN, serverOrigin, Set.of());
    }

    public static BrowserOriginPolicy trustedProxy(String serverOrigin, Set<String> allowedHttpsOrigins) {
        return new BrowserOriginPolicy(
                ManagementAuthenticationMode.TRUSTED_PROXY, serverOrigin, allowedHttpsOrigins);
    }

    public boolean corsEnabled() {
        return mode == ManagementAuthenticationMode.TRUSTED_PROXY && allowedOrigins.size() > 1;
    }

    public ManagementAuthenticationMode mode() {
        return mode;
    }

    public boolean isAllowed(String suppliedOrigin) {
        if (suppliedOrigin == null) {
            return false;
        }
        try {
            return allowedOrigins.contains(normalizeOrigin(suppliedOrigin, false));
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    public void requireAllowed(String suppliedOrigin) {
        if (!isAllowed(suppliedOrigin)) {
            throw originFailure();
        }
    }

    public Optional<String> allowCredentialedOrigin(String suppliedOrigin) {
        if (!corsEnabled()) {
            return Optional.empty();
        }
        try {
            String normalized = normalizeOrigin(suppliedOrigin, false);
            return allowedOrigins.contains(normalized) && !serverOrigin.equals(normalized)
                    ? Optional.of(normalized)
                    : Optional.empty();
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    public String serverOrigin() {
        return serverOrigin;
    }

    private static String normalizeOrigin(String supplied, boolean requireHttps) {
        String value = Objects.requireNonNull(supplied, "origin").trim();
        if (value.equals("*")) {
            throw new IllegalArgumentException("Wildcard origins are prohibited");
        }
        try {
            URI uri = new URI(value);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (scheme == null || host == null
                    || uri.getRawUserInfo() != null
                    || (uri.getRawPath() != null && !uri.getRawPath().isEmpty())
                    || uri.getRawQuery() != null
                    || uri.getRawFragment() != null
                    || host.endsWith(".")) {
                throw new IllegalArgumentException("Origin must contain only scheme, host, and optional port");
            }
            scheme = scheme.toLowerCase(Locale.ROOT);
            host = host.toLowerCase(Locale.ROOT);
            if (!scheme.equals("http") && !scheme.equals("https")) {
                throw new IllegalArgumentException("Origin scheme must be HTTP or HTTPS");
            }
            if (requireHttps && !scheme.equals("https")) {
                throw new IllegalArgumentException("Cross-origin management access requires HTTPS");
            }
            int port = uri.getPort();
            int defaultPort = scheme.equals("https") ? 443 : 80;
            String hostPart = host.indexOf(':') >= 0 ? "[" + host + "]" : host;
            return scheme + "://" + hostPart + (port == -1 || port == defaultPort ? "" : ":" + port);
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException("Invalid origin", exception);
        }
    }

    private static ManagementSecurityException originFailure() {
        return new ManagementSecurityException(
                403, "ORIGIN_VALIDATION_FAILED", "Request origin is not allowed");
    }
}
