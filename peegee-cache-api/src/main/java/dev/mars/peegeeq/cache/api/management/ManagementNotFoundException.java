package dev.mars.peegeeq.cache.api.management;

import java.util.Objects;

/** Typed metadata/reveal failure for a missing or non-visible management resource. */
public final class ManagementNotFoundException extends RuntimeException {

    public enum Resource {
        ENTRY,
        COUNTER,
        LOCK
    }

    private final Resource resource;

    public ManagementNotFoundException(Resource resource) {
        super(Objects.requireNonNull(resource, "resource").name() + " was not found");
        this.resource = resource;
    }

    public Resource resource() {
        return resource;
    }
}
