package dev.mars.peegeeq.cache.rest.protocol;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;

import java.io.IOException;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/** Pure validation and representation rules shared by management HTTP routes. */
public final class ManagementWireRules {

    private static final Pattern DECIMAL_LONG = Pattern.compile("-?(0|[1-9][0-9]*)");
    private static final JsonFactory JSON_FACTORY = JsonFactory.builder().build();

    private ManagementWireRules() {
    }

    public static String decimalString(long value) {
        return Long.toString(value);
    }

    public static long parseDecimalString(String value) {
        if (value == null || !DECIMAL_LONG.matcher(value).matches()) {
            throw new IllegalArgumentException("value must be a canonical signed 64-bit decimal string");
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("decimal value exceeds the signed 64-bit range", exception);
        }
    }

    public static String utcTimestamp(Instant instant) {
        return DateTimeFormatter.ISO_INSTANT.format(Objects.requireNonNull(instant, "instant"));
    }

    public static void requireJsonContentType(String contentType) {
        if (contentType == null) {
            throw unsupportedMediaType();
        }
        String[] sections = contentType.toLowerCase(Locale.ROOT).split(";", -1);
        if (!sections[0].trim().equals("application/json") || sections.length > 2
                || (sections.length == 2 && !sections[1].trim().equals("charset=utf-8"))) {
            throw unsupportedMediaType();
        }
    }

    public static void requireRequestSize(long actualBytes, long maximumBytes) {
        if (actualBytes < 0 || maximumBytes < 0) {
            throw new IllegalArgumentException("request sizes must be non-negative");
        }
        if (actualBytes > maximumBytes) {
            throw new ManagementProtocolException(413, "REQUEST_TOO_LARGE", "Request body exceeds the endpoint limit.");
        }
    }

    public static void requireValidJson(String json) {
        Objects.requireNonNull(json, "json");
        try (JsonParser parser = JSON_FACTORY.createParser(json)) {
            JsonToken first = parser.nextToken();
            if (first == null) {
                throw invalidJson();
            }
            parser.skipChildren();
            if (parser.nextToken() != null) {
                throw invalidJson();
            }
        } catch (IOException exception) {
            throw invalidJson();
        }
    }

    public static String correlationId(String requestedId, Supplier<String> generatedId) {
        Objects.requireNonNull(generatedId, "generatedId");
        if (isValidCorrelationId(requestedId)) {
            return requestedId;
        }
        String generated = generatedId.get();
        if (!isValidCorrelationId(generated)) {
            throw new IllegalStateException("generated correlation identifier is invalid");
        }
        return generated;
    }

    public static String escapeLikePrefix(String prefix) {
        Objects.requireNonNull(prefix, "prefix");
        return prefix.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static boolean isValidCorrelationId(String value) {
        if (value == null || value.isEmpty() || value.length() > 128) {
            return false;
        }
        return value.codePoints().noneMatch(Character::isISOControl);
    }

    private static ManagementProtocolException unsupportedMediaType() {
        return new ManagementProtocolException(
                415, "UNSUPPORTED_MEDIA_TYPE", "Content-Type must be application/json with UTF-8 encoding.");
    }

    private static ManagementProtocolException invalidJson() {
        return new ManagementProtocolException(400, "VALIDATION_FAILED", "Request body is not valid JSON.");
    }
}
