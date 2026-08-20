package dev.mars.peegeeq.cache.api.management;

/** Single-use counter preview token and exact count-bound confirmation phrase. */
public record ConfirmedCounterDelete(String previewToken, String confirmationPhrase) {
    public ConfirmedCounterDelete {
        previewToken = ManagementModelValidation.boundedText(previewToken, "previewToken", 32, 512, false);
        confirmationPhrase = ManagementModelValidation.boundedText(
                confirmationPhrase, "confirmationPhrase", 1, 512, false);
        if (!confirmationPhrase.matches("DELETE [1-9][0-9]* COUNTERS")) {
            throw new IllegalArgumentException("confirmationPhrase must match DELETE {count} COUNTERS");
        }
    }
}
