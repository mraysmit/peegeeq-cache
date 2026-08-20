package dev.mars.peegeeq.cache.rest.protocol;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses and renders the management API's strong, single-version entity tags. */
public final class ManagementEntityTagCodec {

    private static final Pattern EXACT_TAG = Pattern.compile("\\\"v(0|[1-9][0-9]*)\\\"");

    private ManagementEntityTagCodec() {
    }

    public static String render(long version) {
        if (version < 0) {
            throw new IllegalArgumentException("version must be non-negative");
        }
        return "\"v" + version + "\"";
    }

    public static long parseExact(String value) {
        if (value == null) {
            throw new IllegalArgumentException("entity tag is required");
        }
        Matcher matcher = EXACT_TAG.matcher(value);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("entity tag must be one strong tag in the form \"v{decimal}\"");
        }
        try {
            return Long.parseLong(matcher.group(1));
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("entity-tag version exceeds the signed 64-bit range", exception);
        }
    }

    public static long requireExactIfMatch(String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank()) {
            throw new ManagementProtocolException(
                    428, "PRECONDITION_REQUIRED", "An exact If-Match entity tag is required.");
        }
        return parseExact(ifMatch);
    }

    public static ManagementPrecondition parse(
            String ifMatch,
            String ifNoneMatch,
            boolean allowIfMatchWildcard,
            boolean allowIfNoneMatchWildcard) {
        if (ifMatch != null && ifNoneMatch != null) {
            throw new IllegalArgumentException("If-Match and If-None-Match cannot be combined");
        }
        if (ifMatch != null) {
            if ("*".equals(ifMatch)) {
                if (!allowIfMatchWildcard) {
                    throw new IllegalArgumentException("If-Match wildcard is not allowed for this operation");
                }
                return ManagementPrecondition.requirePresent();
            }
            return ManagementPrecondition.exact(parseExact(ifMatch));
        }
        if (ifNoneMatch != null) {
            if (!"*".equals(ifNoneMatch) || !allowIfNoneMatchWildcard) {
                throw new IllegalArgumentException("Only an allowed If-None-Match wildcard is supported");
            }
            return ManagementPrecondition.requireAbsent();
        }
        return ManagementPrecondition.none();
    }
}
