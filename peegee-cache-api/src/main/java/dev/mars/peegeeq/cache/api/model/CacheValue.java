package dev.mars.peegeeq.cache.api.model;

import io.vertx.core.buffer.Buffer;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

public record CacheValue(ValueType type, Buffer binaryValue, Long longValue) {

    public CacheValue {
        Objects.requireNonNull(type, "type must not be null");

        if (type == ValueType.LONG) {
            Objects.requireNonNull(longValue, "longValue must not be null for LONG values");
            if (binaryValue != null) {
                throw new IllegalArgumentException("binaryValue must be null for LONG values");
            }
        } else {
            Objects.requireNonNull(binaryValue, "binaryValue must not be null for binary-backed values");
            if (longValue != null) {
                throw new IllegalArgumentException("longValue must be null for non-LONG values");
            }
        }
    }

    public static CacheValue ofBytes(byte[] bytes) {
        return new CacheValue(ValueType.BYTES, Buffer.buffer(bytes), null);
    }

    public static CacheValue ofString(String value) {
        return new CacheValue(ValueType.STRING, Buffer.buffer(value, StandardCharsets.UTF_8.name()), null);
    }

    public static CacheValue ofJsonUtf8(String json) {
        return new CacheValue(ValueType.JSON, Buffer.buffer(json, StandardCharsets.UTF_8.name()), null);
    }

    public static CacheValue ofLong(long value) {
        return new CacheValue(ValueType.LONG, null, value);
    }

    public String asString() {
        if (binaryValue == null) {
            throw new IllegalStateException("Value is not binary-backed");
        }
        return binaryValue.toString(StandardCharsets.UTF_8);
    }

    public long asLong() {
        if (longValue == null) {
            throw new IllegalStateException("Value is not LONG");
        }
        return longValue;
    }
}
