package dev.mars.peegeeq.cache.api.management;

/** Versioned keyed fingerprint safe for the default structured audit event. */
public record ManagementAuditFingerprint(String algorithmVersion, String keyId, String digest) {
    public ManagementAuditFingerprint {
        algorithmVersion = ManagementModelValidation.boundedText(
                algorithmVersion, "algorithmVersion", 1, 64, false);
        keyId = ManagementModelValidation.boundedText(keyId, "keyId", 1, 128, false);
        digest = ManagementModelValidation.boundedText(digest, "digest", 1, 128, false);
        if (!digest.matches("[A-Za-z0-9_-]+")) {
            throw new IllegalArgumentException("digest must be unpadded Base64 URL data");
        }
    }
}
