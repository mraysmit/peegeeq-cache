package dev.mars.peegeeq.cache.api.management;

/** Non-secret lookup name for externally managed management key material. */
public record ManagementSecretReference(String reference) {
    public ManagementSecretReference {
        reference = ManagementModelValidation.boundedText(reference, "reference", 1, 128, true);
    }
}
