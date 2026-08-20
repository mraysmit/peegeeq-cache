package dev.mars.peegeeq.cache.rest.protocol;

import java.net.URI;
import java.util.List;
import java.util.Objects;

/** Safe RFC 9457-style management error representation. */
public record ManagementProblem(
        URI type,
        String title,
        int status,
        String code,
        String detail,
        String instance,
        String correlationId,
        List<FieldError> fieldErrors) {

    public ManagementProblem {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(detail, "detail");
        Objects.requireNonNull(instance, "instance");
        Objects.requireNonNull(correlationId, "correlationId");
        fieldErrors = List.copyOf(Objects.requireNonNull(fieldErrors, "fieldErrors"));
        if (status < 400 || status > 599) {
            throw new IllegalArgumentException("status must be an HTTP error status");
        }
    }

    public static ManagementProblem from(Throwable failure, String instance, String correlationId) {
        Objects.requireNonNull(failure, "failure");
        if (failure instanceof ManagementProtocolException protocol) {
            return create(protocol.status(), protocol.code(), titleFor(protocol.code()),
                    protocol.getMessage(), instance, correlationId);
        }
        return create(500, "INTERNAL_ERROR", "Internal server error",
                "The request could not be completed.", instance, correlationId);
    }

    private static ManagementProblem create(
            int status, String code, String title, String detail, String instance, String correlationId) {
        return new ManagementProblem(
                URI.create("https://peegeeq.dev/problems/" + code.toLowerCase(java.util.Locale.ROOT).replace('_', '-')),
                title, status, code, detail, instance, correlationId, List.of());
    }

    private static String titleFor(String code) {
        return switch (code) {
            case "PRECONDITION_REQUIRED" -> "Precondition required";
            case "UNSUPPORTED_MEDIA_TYPE" -> "Unsupported media type";
            case "REQUEST_TOO_LARGE" -> "Request too large";
            case "INVALID_CURSOR", "CURSOR_SCOPE_MISMATCH" -> "Invalid cursor";
            default -> "Request validation failed";
        };
    }

    public record FieldError(String field, String code, String detail) {
        public FieldError {
            Objects.requireNonNull(field, "field");
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(detail, "detail");
        }
    }
}
