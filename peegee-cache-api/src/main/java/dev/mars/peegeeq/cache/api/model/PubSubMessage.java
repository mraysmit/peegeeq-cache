package dev.mars.peegeeq.cache.api.model;

public record PubSubMessage(
        String channel,
        String payload,
        String contentType,
        long receivedAtEpochMillis
) {}
