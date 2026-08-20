package dev.mars.peegeeq.cache.api.management;

import dev.mars.peegeeq.cache.api.exception.CacheException;

import java.util.Objects;

/** Typed failure for a management operation unsupported by the selected cache implementation. */
public final class ManagementCapabilityException extends CacheException {
    private final ManagementCapability capability;

    public ManagementCapabilityException(ManagementCapability capability) {
        super("Management capability is unavailable: " + Objects.requireNonNull(capability, "capability"));
        this.capability = capability;
    }

    public ManagementCapability capability() {
        return capability;
    }
}
