package dev.mars.peegeeq.cache.rest.protocol;

import java.time.Clock;
import java.time.Duration;

/**
 * HTTP protocol adapter over the shared management API cursor codec.
 *
 * <p>The shared codec owns the wire format, HMAC verification, expiry enforcement,
 * scope binding, and typed keyset position. This adapter only converts between the
 * REST protocol records and their API equivalents, then translates cursor failures
 * into stable HTTP-facing error codes: {@code INVALID_CURSOR} or
 * {@code CURSOR_SCOPE_MISMATCH}. It deliberately contains no duplicate cryptographic,
 * serialization, pagination, or SQL logic.</p>
 */
public final class ManagementCursorCodec {

    private final dev.mars.peegeeq.cache.api.management.ManagementCursorCodec delegate;

    public ManagementCursorCodec(byte[] key, Clock clock, Duration lifetime) {
        delegate = new dev.mars.peegeeq.cache.api.management.ManagementCursorCodec(
                key, clock, lifetime);
    }

    public String encode(ManagementCursorScope scope, ManagementCursorPosition position) {
        return delegate.encode(toApi(scope), toApi(position));
    }

    public ManagementCursorPosition decode(String cursor, ManagementCursorScope expectedScope) {
        try {
            return fromApi(delegate.decode(cursor, toApi(expectedScope)));
        } catch (dev.mars.peegeeq.cache.api.management.ManagementCursorException failure) {
            String code = failure.code()
                    == dev.mars.peegeeq.cache.api.management.ManagementCursorException.Code.SCOPE_MISMATCH
                    ? "CURSOR_SCOPE_MISMATCH"
                    : "INVALID_CURSOR";
            throw new ManagementProtocolException(400, code, failure.getMessage());
        }
    }

    private static dev.mars.peegeeq.cache.api.management.ManagementCursorScope toApi(
            ManagementCursorScope scope) {
        return new dev.mars.peegeeq.cache.api.management.ManagementCursorScope(
                scope.endpoint(), scope.setupId(), scope.namespace(), scope.filters(), scope.sort());
    }

    private static dev.mars.peegeeq.cache.api.management.ManagementCursorPosition toApi(
            ManagementCursorPosition position) {
        return new dev.mars.peegeeq.cache.api.management.ManagementCursorPosition(
                dev.mars.peegeeq.cache.api.management.ManagementCursorPosition.Kind.valueOf(
                        position.kind().name()),
                position.entryCount(),
                position.identifier());
    }

    private static ManagementCursorPosition fromApi(
            dev.mars.peegeeq.cache.api.management.ManagementCursorPosition position) {
        return new ManagementCursorPosition(
                ManagementCursorPosition.Kind.valueOf(position.kind().name()),
                position.entryCount(),
                position.identifier());
    }
}
