package dev.mars.peegeeq.cache.rest.protocol;

import dev.mars.peegeeq.cache.rest.security.ManagementAuthenticationException;
import dev.mars.peegeeq.cache.rest.security.ManagementRateLimitException;
import dev.mars.peegeeq.cache.rest.security.ManagementSecurityException;
import dev.mars.peegeeq.cache.rest.server.SetupRegistryException;
import dev.mars.peegeeq.cache.rest.server.PubSubManagementException;
import dev.mars.peegeeq.cache.api.management.ManagementAuditException;
import dev.mars.peegeeq.cache.api.management.ManagementAuditOutcomeException;
import dev.mars.peegeeq.cache.api.management.ManagementBulkDeleteException;
import dev.mars.peegeeq.cache.api.management.ManagementCounterException;
import dev.mars.peegeeq.cache.api.management.ManagementNotFoundException;
import dev.mars.peegeeq.cache.api.management.ManagementReadinessException;

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
        if (failure instanceof ManagementAuthenticationException authentication) {
            return create(authentication.status(), authentication.code(), "Authentication failed",
                    authentication.getMessage(), instance, correlationId);
        }
        if (failure instanceof ManagementSecurityException security) {
            return create(security.status(), security.code(), "Security policy rejected request",
                    security.getMessage(), instance, correlationId);
        }
        if (failure instanceof ManagementRateLimitException rateLimit) {
            return create(rateLimit.status(), rateLimit.code(), "Rate limit exceeded",
                    rateLimit.getMessage(), instance, correlationId);
        }
        if (failure instanceof SetupRegistryException registry) {
            return create(registry.status(), registry.code(), "Setup operation failed",
                    registry.getMessage(), instance, correlationId);
        }
        if (failure instanceof PubSubManagementException pubSub) {
            return switch (pubSub.code()) {
                case NOT_FOUND -> create(404, "SUBSCRIPTION_NOT_FOUND", "Resource not found",
                        "The subscription was not found.", instance, correlationId);
                case LIMIT_REACHED -> create(429, "SUBSCRIPTION_LIMIT_REACHED", "Resource limit exceeded",
                        "The subscription limit has been reached.", instance, correlationId);
                case EXPIRED -> create(410, "MESSAGE_EXPIRED", "Retained message expired",
                        "The retained message is no longer available.", instance, correlationId);
            };
        }
        if (failure instanceof ManagementNotFoundException notFound) {
            String code = notFound.resource().name() + "_NOT_FOUND";
            return create(404, code, "Resource not found",
                    notFound.resource().name().toLowerCase(java.util.Locale.ROOT)
                            + " was not found.", instance, correlationId);
        }
        if (failure instanceof ManagementReadinessException readiness) {
            return create(503, readiness.code().name(), "Management data unavailable",
                    "The management read model is not ready.", instance, correlationId);
        }
        if (failure instanceof ManagementAuditOutcomeException) {
            return create(503, "AUDIT_OUTCOME_UNAVAILABLE", "Audit outcome unavailable",
                    "The operation may have committed but its authoritative audit outcome is unavailable.",
                    instance, correlationId);
        }
        if (failure instanceof ManagementAuditException) {
            return create(503, "AUDIT_UNAVAILABLE", "Audit unavailable",
                    "The authoritative management audit journal is unavailable.", instance, correlationId);
        }
        if (failure instanceof ManagementBulkDeleteException bulk) {
            int status = switch (bulk.code()) {
                case CONFIRMATION_MISMATCH -> 400;
                case SCOPE_MISMATCH -> 403;
                case TOKEN_INVALID, TOKEN_EXPIRED, SCOPE_TOO_LARGE -> 409;
            };
            String code = switch (bulk.code()) {
                case SCOPE_TOO_LARGE -> "BULK_SCOPE_CONFLICT";
                case CONFIRMATION_MISMATCH -> "CONFIRMATION_MISMATCH";
                case SCOPE_MISMATCH -> "PREVIEW_SCOPE_MISMATCH";
                case TOKEN_INVALID -> "PREVIEW_TOKEN_INVALID";
                case TOKEN_EXPIRED -> "PREVIEW_TOKEN_EXPIRED";
            };
            return create(status, code, "Bulk operation rejected",
                    "The bulk preview cannot be used for this request.", instance, correlationId);
        }
        if (failure instanceof ManagementCounterException counter
                && counter.code() == ManagementCounterException.Code.OVERFLOW) {
            return create(409, "COUNTER_OVERFLOW", "Counter operation rejected",
                    "The counter operation would exceed the signed 64-bit range.",
                    instance, correlationId);
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
