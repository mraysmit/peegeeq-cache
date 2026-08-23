package dev.mars.peegeeq.cache.rest.security;

import java.util.Objects;

/** One-time bootstrap material returned directly to the controlling process. */
public record LocalTokenBootstrap(LocalTokenSessionManager manager, String token) {

    public LocalTokenBootstrap {
        manager = Objects.requireNonNull(manager, "manager");
        token = Objects.requireNonNull(token, "token");
    }
}
