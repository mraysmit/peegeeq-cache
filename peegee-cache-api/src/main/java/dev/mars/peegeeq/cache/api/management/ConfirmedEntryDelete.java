package dev.mars.peegeeq.cache.api.management;

/** Single-use entry preview token and its exact server-issued confirmation phrase. */
public record ConfirmedEntryDelete(String previewToken, String confirmationPhrase) {
    public ConfirmedEntryDelete {
        previewToken = ManagementModelValidation.boundedText(previewToken, "previewToken", 32, 512, false);
        confirmationPhrase = ManagementModelValidation.boundedText(
                confirmationPhrase, "confirmationPhrase", 1, 512, false);
    }
}
