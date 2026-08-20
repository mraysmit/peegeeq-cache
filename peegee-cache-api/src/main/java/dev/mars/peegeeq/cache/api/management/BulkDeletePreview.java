package dev.mars.peegeeq.cache.api.management;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Actor-bound, expiring preview of an exact versioned deletion set. */
public record BulkDeletePreview(
        String previewToken,
        Instant expiresAt,
        String setupId,
        String namespace,
        long resolvedCount,
        long totalBytes,
        List<String> sampleKeys,
        String confirmationPhrase) {
    public BulkDeletePreview {
        previewToken = ManagementModelValidation.boundedText(previewToken, "previewToken", 32, 512, false);
        Objects.requireNonNull(expiresAt, "expiresAt");
        setupId = ManagementModelValidation.boundedText(setupId, "setupId", 1, 64, false);
        ManagementModelValidation.nonNegativeVersion(resolvedCount, "resolvedCount");
        ManagementModelValidation.nonNegativeVersion(totalBytes, "totalBytes");
        sampleKeys = List.copyOf(Objects.requireNonNull(sampleKeys, "sampleKeys"));
        if (sampleKeys.size() > 20) {
            throw new IllegalArgumentException("sampleKeys must contain at most 20 entries");
        }
        confirmationPhrase = ManagementModelValidation.boundedText(
                confirmationPhrase, "confirmationPhrase", 1, 512, false);
    }
}
