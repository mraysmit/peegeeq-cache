package dev.mars.peegeeq.cache.rest.server;

import dev.mars.peegeeq.cache.api.management.ManagementSecretReference;
import dev.mars.peegeeq.cache.rest.security.SetupTarget;

import java.util.Objects;
import java.util.regex.Pattern;

/** Setup connection metadata. Password material is deliberately held separately. */
public record SetupDefinition(
        String setupId,
        String displayName,
        SetupTarget target,
        String database,
        String schema,
        String username,
        int poolMaxSize,
        SetupRuntimeConfiguration runtimeConfiguration,
        SetupSource source,
        ManagementSecretReference secretReference) {

    private static final Pattern ID = Pattern.compile("[a-z][a-z0-9-]{0,62}");
    private static final Pattern SCHEMA = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,62}");

    public SetupDefinition {
        setupId = requireMatching(setupId, "setupId", ID);
        displayName = requireBoundedText(displayName, "displayName", 128);
        target = Objects.requireNonNull(target, "target");
        database = requireBoundedText(database, "database", 128);
        schema = requireMatching(schema, "schema", SCHEMA);
        username = requireBoundedText(username, "username", 128);
        runtimeConfiguration = Objects.requireNonNull(runtimeConfiguration, "runtimeConfiguration");
        source = Objects.requireNonNull(source, "source");
        if (poolMaxSize < 1 || poolMaxSize > 100) {
            throw new IllegalArgumentException("poolMaxSize must be between 1 and 100");
        }
        if ((source == SetupSource.CONFIGURED) != (secretReference != null)) {
            throw new IllegalArgumentException("Only configured setups use a secret reference");
        }
    }

    public SetupDefinition(
            String setupId,
            String displayName,
            SetupTarget target,
            String database,
            String schema,
            String username,
            int poolMaxSize,
            SetupSource source,
            ManagementSecretReference secretReference) {
        this(setupId, displayName, target, database, schema, username, poolMaxSize,
                SetupRuntimeConfiguration.defaults(), source, secretReference);
    }

    private static String requireMatching(String value, String name, Pattern pattern) {
        String text = Objects.requireNonNull(value, name);
        if (!pattern.matcher(text).matches()) {
            throw new IllegalArgumentException("Invalid " + name);
        }
        return text;
    }

    private static String requireBoundedText(String value, String name, int maximumLength) {
        String text = Objects.requireNonNull(value, name).trim();
        if (text.isEmpty() || text.length() > maximumLength
                || text.chars().anyMatch(character -> character < 0x20)) {
            throw new IllegalArgumentException("Invalid " + name);
        }
        return text;
    }
}
