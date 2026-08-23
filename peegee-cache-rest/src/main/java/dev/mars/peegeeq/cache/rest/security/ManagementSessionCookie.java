package dev.mars.peegeeq.cache.rest.security;

import java.time.Instant;
import java.util.Objects;

public record ManagementSessionCookie(
        String name,
        String value,
        String path,
        boolean httpOnly,
        boolean secure,
        String sameSite,
        Instant expiresAt) {

    public static final String COOKIE_NAME = "PGQMGMTSESSION";

    public ManagementSessionCookie {
        name = Objects.requireNonNull(name, "name");
        value = Objects.requireNonNull(value, "value");
        path = Objects.requireNonNull(path, "path");
        sameSite = Objects.requireNonNull(sameSite, "sameSite");
        expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
    }
}
