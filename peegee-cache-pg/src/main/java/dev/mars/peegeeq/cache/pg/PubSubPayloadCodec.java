package dev.mars.peegeeq.cache.pg;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** Versioned wire envelope for typed PeeGeeQ Cache notifications. */
public final class PubSubPayloadCodec {

    private static final String PREFIX = "__PGQ_CACHE_TYPED_V1__:";

    private PubSubPayloadCodec() {
    }

    public static String encode(String payload, String contentType) {
        if (contentType == null && !payload.startsWith(PREFIX)) {
            return payload;
        }
        byte[] typeBytes = contentType == null
                ? new byte[0]
                : contentType.getBytes(StandardCharsets.UTF_8);
        byte[] payloadBytes = payload.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buffer = ByteBuffer.allocate(1 + Integer.BYTES + typeBytes.length + payloadBytes.length);
        buffer.put((byte) (contentType == null ? 0 : 1));
        buffer.putInt(typeBytes.length);
        buffer.put(typeBytes);
        buffer.put(payloadBytes);
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(buffer.array());
    }

    public static Decoded decode(String wirePayload) {
        if (!wirePayload.startsWith(PREFIX)) {
            return new Decoded(wirePayload, null);
        }
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(wirePayload.substring(PREFIX.length()));
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            byte hasContentType = buffer.get();
            int typeLength = buffer.getInt();
            if ((hasContentType != 0 && hasContentType != 1)
                    || typeLength < 0
                    || typeLength > buffer.remaining()) {
                return new Decoded(wirePayload, null);
            }
            byte[] typeBytes = new byte[typeLength];
            buffer.get(typeBytes);
            byte[] payloadBytes = new byte[buffer.remaining()];
            buffer.get(payloadBytes);
            String contentType = hasContentType == 0
                    ? null
                    : new String(typeBytes, StandardCharsets.UTF_8);
            return new Decoded(new String(payloadBytes, StandardCharsets.UTF_8), contentType);
        } catch (IllegalArgumentException | java.nio.BufferUnderflowException failure) {
            return new Decoded(wirePayload, null);
        }
    }

    public record Decoded(String payload, String contentType) {
    }
}
