package dev.mars.peegeeq.cache.rest.security;

import io.vertx.core.MultiMap;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** Authenticates management identities asserted by a configured immediate reverse proxy. */
public final class TrustedProxyAuthenticator {

    private static final int MAX_ACTOR_CODE_POINTS = 128;
    private static final int MAX_ROLE_VALUES = 16;
    private static final int MAX_ROLE_BYTES = 256;

    private final TrustedProxyAuthenticationConfig config;

    public TrustedProxyAuthenticator(TrustedProxyAuthenticationConfig config) {
        this.config = Objects.requireNonNull(config, "config");
    }

    public AuthenticatedManagementIdentity authenticate(InetAddress immediatePeer, MultiMap headers) {
        Objects.requireNonNull(immediatePeer, "immediatePeer");
        Objects.requireNonNull(headers, "headers");

        List<String> users = headers.getAll(config.userHeaderName());
        List<String> roles = headers.getAll(config.rolesHeaderName());
        boolean suppliedIdentity = !users.isEmpty() || !roles.isEmpty();
        if (!suppliedIdentity) {
            throw authenticationRequired();
        }
        if (!config.trusts(immediatePeer)) {
            throw new ManagementAuthenticationException(
                    401, "UNTRUSTED_IDENTITY_SOURCE", "Identity headers require a trusted proxy");
        }
        if (users.size() != 1 || roles.size() != 1) {
            throw invalidIdentity();
        }

        String actor = users.getFirst().trim();
        if (!isValidActor(actor)) {
            throw invalidIdentity();
        }
        Set<String> normalizedRoles = parseRoles(roles.getFirst());
        return new AuthenticatedManagementIdentity(actor, normalizedRoles, immediatePeer.getHostAddress());
    }

    private Set<String> parseRoles(String suppliedRoles) {
        if (suppliedRoles.getBytes(StandardCharsets.UTF_8).length > MAX_ROLE_BYTES) {
            throw invalidIdentity();
        }
        String[] values = suppliedRoles.split(",", -1);
        if (values.length == 0 || values.length > MAX_ROLE_VALUES) {
            throw invalidIdentity();
        }
        LinkedHashSet<String> roles = new LinkedHashSet<>();
        for (String value : values) {
            String role = value.trim().toLowerCase(Locale.ROOT);
            if (role.isEmpty() || !config.allowedRoles().contains(role) || !roles.add(role)) {
                throw invalidIdentity();
            }
        }
        return Set.copyOf(roles);
    }

    private static boolean isValidActor(String actor) {
        int length = actor.codePointCount(0, actor.length());
        if (length == 0 || length > MAX_ACTOR_CODE_POINTS) {
            return false;
        }
        return actor.codePoints().allMatch(TrustedProxyAuthenticator::isPrintable);
    }

    private static boolean isPrintable(int codePoint) {
        int type = Character.getType(codePoint);
        return !Character.isISOControl(codePoint)
                && type != Character.CONTROL
                && type != Character.FORMAT
                && type != Character.SURROGATE
                && type != Character.PRIVATE_USE
                && type != Character.UNASSIGNED
                && type != Character.LINE_SEPARATOR
                && type != Character.PARAGRAPH_SEPARATOR;
    }

    private static ManagementAuthenticationException authenticationRequired() {
        return new ManagementAuthenticationException(401, "AUTHENTICATION_REQUIRED", "Authentication required");
    }

    private static ManagementAuthenticationException invalidIdentity() {
        return new ManagementAuthenticationException(401, "INVALID_IDENTITY", "Invalid trusted identity headers");
    }
}
