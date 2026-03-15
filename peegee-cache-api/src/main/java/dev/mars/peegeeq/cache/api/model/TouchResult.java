package dev.mars.peegeeq.cache.api.model;

public record TouchResult(
        boolean updated,
        TtlResult ttl
) {}
