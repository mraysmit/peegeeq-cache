package dev.mars.peegeeq.cache.pg.logging;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Produces bounded, non-raw representations of values that may appear in logs. */
public final class SafeLogValue {

    private static final int FINGERPRINT_BYTES = 8;

    private SafeLogValue() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Returns a deterministic fingerprint suitable for correlating a non-secret identifier.
     * The raw identifier is never included.
     */
    public static String identifier(String value) {
        if (value == null) {
            return "id:null";
        }
        byte[] utf8 = value.getBytes(StandardCharsets.UTF_8);
        byte[] digest;
        try {
            digest = MessageDigest.getInstance("SHA-256").digest(utf8);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
        return "id:sha256:" + HexFormat.of().formatHex(digest, 0, FINGERPRINT_BYTES)
                + ":bytes=" + utf8.length;
    }

    /** Returns the only permitted representation of secret material. */
    public static String secret() {
        return "[REDACTED]";
    }
}
