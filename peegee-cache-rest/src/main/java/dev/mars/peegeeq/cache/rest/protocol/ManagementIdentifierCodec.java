package dev.mars.peegeeq.cache.rest.protocol;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Objects;
import java.util.regex.Pattern;

/** Encodes management path identifiers as canonical unpadded Base64 URL values. */
public final class ManagementIdentifierCodec {

    private static final int MAX_NAMESPACE_BYTES = 128;
    private static final int MAX_KEY_BYTES = 1_024;
    private static final Pattern BASE64_URL = Pattern.compile("[A-Za-z0-9_-]+");

    private ManagementIdentifierCodec() {
    }

    public static String encodeNamespace(String namespace) {
        return encode(namespace, MAX_NAMESPACE_BYTES, "namespace");
    }

    public static String decodeNamespace(String encodedNamespace) {
        return decode(encodedNamespace, MAX_NAMESPACE_BYTES, "namespace");
    }

    public static String encodeKey(String key) {
        return encode(key, MAX_KEY_BYTES, "key");
    }

    public static String decodeKey(String encodedKey) {
        return decode(encodedKey, MAX_KEY_BYTES, "key");
    }

    private static String encode(String identifier, int maximumBytes, String label) {
        Objects.requireNonNull(identifier, label);
        byte[] bytes = identifier.getBytes(StandardCharsets.UTF_8);
        validateDecoded(identifier, bytes, maximumBytes, label);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String decode(String encoded, int maximumBytes, String label) {
        Objects.requireNonNull(encoded, "encoded " + label);
        if (!BASE64_URL.matcher(encoded).matches() || encoded.length() % 4 == 1) {
            throw invalid(label, "must be canonical unpadded Base64 URL data");
        }

        final byte[] bytes;
        try {
            bytes = Base64.getUrlDecoder().decode(encoded);
        } catch (IllegalArgumentException exception) {
            throw invalid(label, "must be canonical unpadded Base64 URL data", exception);
        }
        if (!Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).equals(encoded)) {
            throw invalid(label, "must use canonical unpadded Base64 URL encoding");
        }

        final String identifier;
        try {
            identifier = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException exception) {
            throw invalid(label, "must contain valid UTF-8", exception);
        }
        validateDecoded(identifier, bytes, maximumBytes, label);
        return identifier;
    }

    private static void validateDecoded(String identifier, byte[] bytes, int maximumBytes, String label) {
        if (bytes.length == 0) {
            throw invalid(label, "must not be empty");
        }
        if (bytes.length > maximumBytes) {
            throw invalid(label, "must not exceed " + maximumBytes + " UTF-8 bytes");
        }
        if (identifier.indexOf('\0') >= 0) {
            throw invalid(label, "must not contain NUL");
        }
    }

    private static IllegalArgumentException invalid(String label, String detail) {
        return new IllegalArgumentException("Invalid " + label + ": " + detail);
    }

    private static IllegalArgumentException invalid(String label, String detail, Exception cause) {
        return new IllegalArgumentException("Invalid " + label + ": " + detail, cause);
    }
}
