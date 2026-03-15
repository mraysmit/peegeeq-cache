package dev.mars.peegeeq.cache.api.model;

public record PublishRequest(
        String channel,
        String payload,
        String contentType
) {}
