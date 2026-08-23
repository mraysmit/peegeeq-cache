package dev.mars.peegeeq.cache.api.management;

import java.util.Objects;

/** Setup-scoped activity resource; its raw identifier is never suitable for logs or metrics. */
public record ManagementActivityResource(
        ManagementActivityResourceType type,
        String identifier) {

    public ManagementActivityResource {
        Objects.requireNonNull(type, "type");
        identifier = identifier == null ? null
                : ManagementModelValidation.boundedText(identifier, "identifier", 0, 1024, false);
    }
}
