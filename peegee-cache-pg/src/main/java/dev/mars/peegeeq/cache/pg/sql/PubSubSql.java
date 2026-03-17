package dev.mars.peegeeq.cache.pg.sql;

import java.util.Objects;

/**
 * SQL statement constants for pub/sub operations using PostgreSQL LISTEN/NOTIFY.
 */
public final class PubSubSql {

    /** Publish a notification to a channel via pg_notify. */
    public static final String NOTIFY = "SELECT pg_notify($1, $2)";

    private final String channelPrefix;

    public static PubSubSql forPrefix(String channelPrefix) {
        return new PubSubSql(Objects.requireNonNull(channelPrefix, "channelPrefix"));
    }

    private PubSubSql(String channelPrefix) {
        this.channelPrefix = channelPrefix;
    }

    /**
     * Returns the fully qualified channel name: {prefix}__{channel}.
     */
    public String qualifiedChannel(String channel) {
        return channelPrefix + "__" + channel;
    }

    /**
     * Returns a LISTEN command for the given raw channel name.
     * The channel is double-quoted to allow special characters.
     */
    public String listen(String channel) {
        return "LISTEN \"" + qualifiedChannel(channel) + "\"";
    }

    /**
     * Returns an UNLISTEN command for the given raw channel name.
     */
    public String unlisten(String channel) {
        return "UNLISTEN \"" + qualifiedChannel(channel) + "\"";
    }

    /** Returns an UNLISTEN * command to stop listening on all channels. */
    public static String unlistenAll() {
        return "UNLISTEN *";
    }
}
