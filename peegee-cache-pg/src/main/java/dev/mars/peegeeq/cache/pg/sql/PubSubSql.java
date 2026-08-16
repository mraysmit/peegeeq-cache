package dev.mars.peegeeq.cache.pg.sql;

import java.util.Objects;
import java.nio.charset.StandardCharsets;

/**
 * SQL statement constants for pub/sub operations using PostgreSQL LISTEN/NOTIFY.
 */
public final class PubSubSql {

    private static final int POSTGRESQL_IDENTIFIER_MAX_BYTES = 63;

    /** Publish a notification to a channel via pg_notify. */
    public static final String NOTIFY = "SELECT pg_notify($1, $2)";

    private final String channelPrefix;

    public static PubSubSql forPrefix(String channelPrefix) {
        Objects.requireNonNull(channelPrefix, "channelPrefix");
        validateComponent(channelPrefix, "channelPrefix");
        return new PubSubSql(channelPrefix);
    }

    private PubSubSql(String channelPrefix) {
        this.channelPrefix = channelPrefix;
    }

    /**
     * Returns the fully qualified channel name: {prefix}__{channel}.
     */
    public String qualifiedChannel(String channel) {
        Objects.requireNonNull(channel, "channel");
        validateComponent(channel, "channel");
        String qualifiedChannel = channelPrefix + "__" + channel;
        int byteLength = qualifiedChannel.getBytes(StandardCharsets.UTF_8).length;
        if (byteLength > POSTGRESQL_IDENTIFIER_MAX_BYTES) {
            throw new IllegalArgumentException(
                    "qualified pub/sub channel must not exceed 63 UTF-8 bytes (was " + byteLength + ")");
        }
        return qualifiedChannel;
    }

    /**
     * Returns a LISTEN command for the given raw channel name.
     * The channel is double-quoted to allow special characters.
     */
    public String listen(String channel) {
        return "LISTEN " + quoteIdentifier(qualifiedChannel(channel));
    }

    /**
     * Returns an UNLISTEN command for the given raw channel name.
     */
    public String unlisten(String channel) {
        return "UNLISTEN " + quoteIdentifier(qualifiedChannel(channel));
    }

    /** Returns an UNLISTEN * command to stop listening on all channels. */
    public static String unlistenAll() {
        return "UNLISTEN *";
    }

    private static String quoteIdentifier(String identifier) {
        return '"' + identifier.replace("\"", "\"\"") + '"';
    }

    private static void validateComponent(String value, String name) {
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        if (value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException(name + " must not contain NUL characters");
        }
    }
}
