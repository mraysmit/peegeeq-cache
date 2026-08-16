package dev.mars.peegeeq.cache.observability.tracing;

import dev.mars.peegeeq.cache.core.telemetry.CacheOperation;
import dev.mars.peegeeq.cache.core.telemetry.CacheTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenTelemetryCacheTelemetryTest {

    private SdkTracerProvider tracerProvider;

    @AfterEach
    void closeProvider() {
        if (tracerProvider != null) {
            tracerProvider.close();
        }
    }

    @Test
    void exportsSuccessAndFailureSpans() {
        InMemorySpanExporter exporter = InMemorySpanExporter.create();
        tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        OpenTelemetrySdk sdk = OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build();
        OpenTelemetryCacheTelemetry telemetry = new OpenTelemetryCacheTelemetry(sdk);

        CacheTelemetry.OperationSpan success = telemetry.startOperation(CacheOperation.COUNTER_INCREMENT);
        try (CacheTelemetry.Activation ignored = success.activate()) {
            assertTrue(io.opentelemetry.api.trace.Span.current().getSpanContext().isValid());
        }
        success.complete(null);

        CacheTelemetry.OperationSpan failure = telemetry.startOperation(CacheOperation.LOCK_ACQUIRE);
        failure.complete(new IllegalStateException("contention path failed"));

        List<SpanData> spans = exporter.getFinishedSpanItems();
        assertEquals(2, spans.size());
        assertEquals("peegeeq.cache counter.increment", spans.get(0).getName());
        assertEquals("counter.increment", spans.get(0).getAttributes()
                .get(AttributeKey.stringKey("peegeeq.cache.operation")));
        assertEquals(StatusCode.UNSET, spans.get(0).getStatus().getStatusCode());
        assertEquals(StatusCode.ERROR, spans.get(1).getStatus().getStatusCode());
        assertEquals(1, spans.get(1).getEvents().size());
    }
}
