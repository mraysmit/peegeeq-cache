package dev.mars.peegeeq.cache.api.management;

/** Single-use entry preview token, exact confirmation phrase, and route-bound namespace. */
public record ConfirmedEntryDelete(String previewToken, String confirmationPhrase, String namespace) {
    public ConfirmedEntryDelete {
        previewToken = ManagementModelValidation.boundedText(previewToken, "previewToken", 32, 512, false);
        confirmationPhrase = ManagementModelValidation.boundedText(
                confirmationPhrase, "confirmationPhrase", 1, 512, false);
        namespace = ManagementModelValidation.boundedText(namespace, "namespace", 1, 128, false);
    }
}
