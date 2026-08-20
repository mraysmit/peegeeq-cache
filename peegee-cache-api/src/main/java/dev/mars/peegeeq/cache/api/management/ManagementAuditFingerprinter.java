package dev.mars.peegeeq.cache.api.management;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;

/** Produces truncated HMAC-SHA-256 audit fingerprints from externally referenced key material. */
public final class ManagementAuditFingerprinter {
    private final byte[] key;
    private final String keyId;
    private final int bits;

    public ManagementAuditFingerprinter(
            ManagementSecretReference reference,
            ManagementSecretProvider provider,
            String keyId,
            int bits) {
        Objects.requireNonNull(reference, "reference");
        Objects.requireNonNull(provider, "provider");
        if (bits < 128 || bits > 256 || bits % 8 != 0) {
            throw new IllegalArgumentException("fingerprint size must be a byte-aligned 128-256 bits");
        }
        byte[] resolved = Objects.requireNonNull(provider.resolve(reference), "resolved secret");
        try {
            if (resolved.length < 32) {
                throw new IllegalArgumentException("audit fingerprint key must contain at least 256 bits");
            }
            this.key = resolved.clone();
        } finally {
            Arrays.fill(resolved, (byte) 0);
        }
        this.keyId = ManagementModelValidation.boundedText(keyId, "keyId", 1, 128, false);
        this.bits = bits;
    }

    public ManagementAuditFingerprint fingerprint(String identifier) {
        Objects.requireNonNull(identifier, "identifier");
        byte[] digest = hmac(identifier.getBytes(StandardCharsets.UTF_8));
        byte[] truncated = Arrays.copyOf(digest, bits / 8);
        return new ManagementAuditFingerprint(
                "HMAC-SHA-256/" + bits + "/V1",
                keyId,
                Base64.getUrlEncoder().withoutPadding().encodeToString(truncated));
    }

    private byte[] hmac(byte[] input) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(input);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HmacSHA256 is unavailable", exception);
        }
    }
}
