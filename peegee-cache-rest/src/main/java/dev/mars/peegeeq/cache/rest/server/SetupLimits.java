package dev.mars.peegeeq.cache.rest.server;

import dev.mars.peegeeq.cache.api.management.ManagementLimits;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Effective per-setup byte limits the management console needs as data.
 *
 * <p>The Pub/Sub channel limit is derived from the setup's channel prefix: PostgreSQL identifiers
 * are at most 63 bytes, and the runtime joins prefix and channel with a separator. The payload
 * limit is fixed by the notification transport. The value limit comes from server configuration.
 */
public record SetupLimits(int pubSubChannelMaxBytes, int pubSubPayloadMaxBytes, int maximumValueBytes) {

    /** PostgreSQL identifier length limit in bytes. */
    static final int POSTGRES_IDENTIFIER_MAX_BYTES = 63;
    /** Bytes reserved for the prefix/channel separator inside the identifier. */
    static final int CHANNEL_SEPARATOR_BYTES = 2;
    /** Maximum encoded Pub/Sub payload accepted by the publish route. */
    public static final int PUB_SUB_PAYLOAD_MAX_BYTES = 7_500;

    public SetupLimits {
        if (pubSubChannelMaxBytes < 1 || pubSubChannelMaxBytes > POSTGRES_IDENTIFIER_MAX_BYTES
                || pubSubPayloadMaxBytes < 1 || maximumValueBytes < 1) {
            throw new IllegalArgumentException("Setup limits must be positive and valid");
        }
    }

    /** Derives the limits for a setup from its runtime configuration and the server limits. */
    public static SetupLimits from(SetupRuntimeConfiguration configuration, ManagementLimits limits) {
        Objects.requireNonNull(configuration, "configuration");
        Objects.requireNonNull(limits, "limits");
        long maximumValueBytes = limits.maximumValueBytes();
        if (maximumValueBytes > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("maximumValueBytes exceeds the management API integer limit");
        }
        return new SetupLimits(
                channelMaxBytes(configuration), PUB_SUB_PAYLOAD_MAX_BYTES, (int) maximumValueBytes);
    }

    /** Usable channel bytes once the configured prefix and separator are subtracted. */
    static int channelMaxBytes(SetupRuntimeConfiguration configuration) {
        Objects.requireNonNull(configuration, "configuration");
        int prefixBytes = configuration.pubSubChannelPrefix().getBytes(StandardCharsets.UTF_8).length;
        return POSTGRES_IDENTIFIER_MAX_BYTES - prefixBytes - CHANNEL_SEPARATOR_BYTES;
    }
}
