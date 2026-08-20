package dev.mars.peegeeq.cache.api.management;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/** Authenticated identity and request context required by privileged management calls. */
public record ManagementActionContext(
        String actor,
        Set<String> roles,
        String correlationId,
        String sourceAddress) {

    public ManagementActionContext {
        actor = ManagementModelValidation.boundedText(actor, "actor", 1, 128, true);
        correlationId = ManagementModelValidation.boundedText(
                correlationId, "correlationId", 1, 128, false);
        sourceAddress = ManagementModelValidation.boundedText(
                sourceAddress, "sourceAddress", 1, 128, true);
        Objects.requireNonNull(roles, "roles");
        if (roles.isEmpty() || roles.size() > 16) {
            throw new IllegalArgumentException("roles must contain between 1 and 16 values");
        }
        TreeSet<String> normalized = new TreeSet<>();
        int totalBytes = 0;
        for (String role : roles) {
            String value = ManagementModelValidation.boundedText(role, "role", 1, 64, true)
                    .toLowerCase(Locale.ROOT);
            if (!value.matches("[a-z0-9:_-]+")) {
                throw new IllegalArgumentException("role contains unsupported characters");
            }
            normalized.add(value);
            totalBytes += value.getBytes(StandardCharsets.UTF_8).length;
        }
        if (totalBytes > 256) {
            throw new IllegalArgumentException("roles exceed 256 UTF-8 bytes");
        }
        roles = Set.copyOf(normalized);
    }

    public boolean hasRole(String role) {
        Objects.requireNonNull(role, "role");
        return roles.contains(role.toLowerCase(Locale.ROOT));
    }
}
