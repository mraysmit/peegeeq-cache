package dev.mars.peegeeq.cache.rest.server;

import dev.mars.peegeeq.cache.rest.security.TlsMode;

import java.util.Objects;

/** Safe setup metadata. Credentials, usernames, trust profiles, and connection strings are excluded. */
public record SetupSummary(
        String setupId,
        String displayName,
        String host,
        int port,
        String database,
        String schema,
        TlsMode sslMode,
        SetupSource source,
        SetupState state,
        SetupSchemaState schemaState,
        SetupHealthSummary lastHealth) {
    public SetupSummary {
        setupId = Objects.requireNonNull(setupId, "setupId");
        displayName = Objects.requireNonNull(displayName, "displayName");
        host = Objects.requireNonNull(host, "host");
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("port must be between 1 and 65535");
        }
        database = Objects.requireNonNull(database, "database");
        schema = Objects.requireNonNull(schema, "schema");
        sslMode = Objects.requireNonNull(sslMode, "sslMode");
        source = Objects.requireNonNull(source, "source");
        state = Objects.requireNonNull(state, "state");
        schemaState = Objects.requireNonNull(schemaState, "schemaState");
    }
}
