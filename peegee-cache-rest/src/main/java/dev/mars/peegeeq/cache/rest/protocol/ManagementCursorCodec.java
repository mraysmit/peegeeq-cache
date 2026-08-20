package dev.mars.peegeeq.cache.rest.protocol;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Authenticated, expiring cursor codec with no SQL-generation responsibility. */
public final class ManagementCursorCodec {

    private static final int MAGIC = 0x50475143;
    private static final int VERSION = 1;
    private static final int MAX_PAYLOAD_BYTES = 16_384;
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private final byte[] key;
    private final Clock clock;
    private final Duration lifetime;

    public ManagementCursorCodec(byte[] key, Clock clock, Duration lifetime) {
        Objects.requireNonNull(key, "key");
        if (key.length < 32) {
            throw new IllegalArgumentException("cursor authentication key must contain at least 256 bits");
        }
        this.key = key.clone();
        this.clock = Objects.requireNonNull(clock, "clock");
        this.lifetime = Objects.requireNonNull(lifetime, "lifetime");
        if (lifetime.isZero() || lifetime.isNegative()) {
            throw new IllegalArgumentException("cursor lifetime must be positive");
        }
    }

    public String encode(ManagementCursorScope scope, ManagementCursorPosition position) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(position, "position");
        CursorWriter writer = new CursorWriter();
        writer.writeInt(MAGIC);
        writer.writeByte(VERSION);
        writer.writeLong(clock.instant().getEpochSecond());
        writer.writeString(scope.endpoint());
        writer.writeString(scope.setupId());
        writer.writeNullableString(scope.namespace());
        writer.writeString(scope.sort());
        writer.writeInt(scope.filters().size());
        scope.filters().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            writer.writeString(entry.getKey());
            writer.writeString(entry.getValue());
        });
        writer.writeByte(position.kind().ordinal());
        if (position.entryCount() != null) {
            writer.writeLong(position.entryCount());
        }
        writer.writeString(position.identifier());
        byte[] payload = writer.toByteArray();
        if (payload.length > MAX_PAYLOAD_BYTES) {
            throw new IllegalArgumentException("cursor payload exceeds its bounded size");
        }
        return ENCODER.encodeToString(payload) + "." + ENCODER.encodeToString(sign(payload));
    }

    public ManagementCursorPosition decode(String cursor, ManagementCursorScope expectedScope) {
        Objects.requireNonNull(expectedScope, "expectedScope");
        if (cursor == null) {
            throw invalid("Cursor is required.");
        }
        String[] parts = cursor.split("\\.", -1);
        if (parts.length != 2 || parts[0].isEmpty() || parts[1].isEmpty()) {
            throw invalid("Cursor is malformed.");
        }

        final byte[] payload;
        final byte[] suppliedSignature;
        try {
            payload = decodeCanonical(parts[0]);
            suppliedSignature = decodeCanonical(parts[1]);
        } catch (IllegalArgumentException exception) {
            throw invalid("Cursor is malformed.");
        }
        if (payload.length > MAX_PAYLOAD_BYTES
                || !MessageDigest.isEqual(sign(payload), suppliedSignature)) {
            throw invalid("Cursor authentication failed.");
        }

        final DecodedCursor decoded;
        try {
            decoded = decodePayload(payload);
        } catch (RuntimeException exception) {
            throw invalid("Cursor payload is invalid.");
        }
        Instant issuedAt = Instant.ofEpochSecond(decoded.issuedAtEpochSecond());
        Instant now = clock.instant();
        if (issuedAt.isAfter(now.plusSeconds(60)) || !now.isBefore(issuedAt.plus(lifetime))) {
            throw invalid("Cursor has expired.");
        }
        if (!decoded.scope().equals(expectedScope)) {
            throw new ManagementProtocolException(
                    400, "CURSOR_SCOPE_MISMATCH", "Cursor does not match the requested query scope.");
        }
        return decoded.position();
    }

    private DecodedCursor decodePayload(byte[] payload) {
        CursorReader reader = new CursorReader(payload);
        if (reader.readInt() != MAGIC || reader.readUnsignedByte() != VERSION) {
            throw new IllegalArgumentException("unsupported cursor format");
        }
        long issuedAt = reader.readLong();
        String endpoint = reader.readString();
        String setupId = reader.readString();
        String namespace = reader.readNullableString();
        String sort = reader.readString();
        int filterCount = reader.readInt();
        if (filterCount < 0 || filterCount > 32) {
            throw new IllegalArgumentException("invalid cursor filter count");
        }
        Map<String, String> filters = new LinkedHashMap<>();
        for (int index = 0; index < filterCount; index++) {
            if (filters.put(reader.readString(), reader.readString()) != null) {
                throw new IllegalArgumentException("duplicate cursor filter");
            }
        }
        int kindOrdinal = reader.readUnsignedByte();
        if (kindOrdinal >= ManagementCursorPosition.Kind.values().length) {
            throw new IllegalArgumentException("invalid cursor position kind");
        }
        ManagementCursorPosition.Kind kind = ManagementCursorPosition.Kind.values()[kindOrdinal];
        Long entryCount = kind == ManagementCursorPosition.Kind.ENTRY_COUNT_DESC_NAMESPACE_ASC
                ? reader.readLong()
                : null;
        String identifier = reader.readString();
        reader.requireEnd();
        return new DecodedCursor(
                issuedAt,
                new ManagementCursorScope(endpoint, setupId, namespace, filters, sort),
                new ManagementCursorPosition(kind, entryCount, identifier));
    }

    private byte[] sign(byte[] payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(payload);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HmacSHA256 is unavailable", exception);
        }
    }

    private static byte[] decodeCanonical(String value) {
        if (value.indexOf('=') >= 0 || value.length() % 4 == 1) {
            throw new IllegalArgumentException("non-canonical Base64 URL value");
        }
        byte[] decoded = DECODER.decode(value);
        if (!ENCODER.encodeToString(decoded).equals(value)) {
            throw new IllegalArgumentException("non-canonical Base64 URL value");
        }
        return decoded;
    }

    private static ManagementProtocolException invalid(String detail) {
        return new ManagementProtocolException(400, "INVALID_CURSOR", detail);
    }

    private record DecodedCursor(
            long issuedAtEpochSecond,
            ManagementCursorScope scope,
            ManagementCursorPosition position) {
    }

    private static final class CursorWriter {
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();

        void writeByte(int value) {
            output.write(value);
        }

        void writeInt(int value) {
            output.writeBytes(ByteBuffer.allocate(Integer.BYTES).order(ByteOrder.BIG_ENDIAN).putInt(value).array());
        }

        void writeLong(long value) {
            output.writeBytes(ByteBuffer.allocate(Long.BYTES).order(ByteOrder.BIG_ENDIAN).putLong(value).array());
        }

        void writeNullableString(String value) {
            writeByte(value == null ? 0 : 1);
            if (value != null) {
                writeString(value);
            }
        }

        void writeString(String value) {
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            writeInt(bytes.length);
            output.writeBytes(bytes);
        }

        byte[] toByteArray() {
            return output.toByteArray();
        }
    }

    private static final class CursorReader {
        private final ByteBuffer input;

        CursorReader(byte[] payload) {
            input = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN);
        }

        int readUnsignedByte() {
            return Byte.toUnsignedInt(input.get());
        }

        int readInt() {
            return input.getInt();
        }

        long readLong() {
            return input.getLong();
        }

        String readNullableString() {
            int marker = readUnsignedByte();
            if (marker == 0) {
                return null;
            }
            if (marker != 1) {
                throw new IllegalArgumentException("invalid null marker");
            }
            return readString();
        }

        String readString() {
            int length = readInt();
            if (length < 0 || length > input.remaining() || length > MAX_PAYLOAD_BYTES) {
                throw new IllegalArgumentException("invalid string length");
            }
            ByteBuffer bytes = input.slice(input.position(), length);
            input.position(input.position() + length);
            try {
                return StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(bytes)
                        .toString();
            } catch (CharacterCodingException exception) {
                throw new IllegalArgumentException("invalid UTF-8", exception);
            }
        }

        void requireEnd() {
            if (input.hasRemaining()) {
                throw new IllegalArgumentException("trailing cursor data");
            }
        }
    }
}
