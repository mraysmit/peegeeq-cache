package dev.mars.peegeeq.cache.api.management;

import java.util.Objects;
import java.util.Set;

/** Supported operations and effective limits for one management service. */
public record AdminCapabilities(Set<ManagementCapability> supported, ManagementLimits limits) {
    public AdminCapabilities {
        supported = Set.copyOf(Objects.requireNonNull(supported, "supported"));
        Objects.requireNonNull(limits, "limits");
    }

    public boolean supports(ManagementCapability capability) {
        return supported.contains(Objects.requireNonNull(capability, "capability"));
    }

    public static AdminCapabilities unsupported() {
        return new AdminCapabilities(Set.of(), ManagementLimits.defaults());
    }
}
