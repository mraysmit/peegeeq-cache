package dev.mars.peegeeq.cache.rest.security;

import java.util.Objects;
import java.util.Set;

/** Authenticated management identity derived only from an authoritative boundary. */
public record AuthenticatedManagementIdentity(String actor, Set<String> roles, String sourceAddress) {

    public AuthenticatedManagementIdentity {
        actor = Objects.requireNonNull(actor, "actor");
        roles = Set.copyOf(Objects.requireNonNull(roles, "roles"));
        sourceAddress = Objects.requireNonNull(sourceAddress, "sourceAddress");
    }
}
