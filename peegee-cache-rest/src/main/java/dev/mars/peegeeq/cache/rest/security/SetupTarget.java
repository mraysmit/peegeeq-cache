package dev.mars.peegeeq.cache.rest.security;

import java.util.Objects;

public record SetupTarget(String hostname, int port, String trustProfileId, TlsMode tlsMode) {

    public SetupTarget {
        hostname = Objects.requireNonNull(hostname, "hostname");
        trustProfileId = Objects.requireNonNull(trustProfileId, "trustProfileId");
        tlsMode = Objects.requireNonNull(tlsMode, "tlsMode");
        if (tlsMode != TlsMode.VERIFY_FULL) {
            throw new IllegalArgumentException("UI-session database targets require VERIFY_FULL TLS");
        }
    }
}
