package dev.mars.peegeeq.cache.api.management;

import java.util.Objects;

/** Bounded terminal audit result without exception text or sensitive fields. */
public record ManagementAuditOutcome(
        ManagementAuditTerminalOutcome outcome,
        String code,
        Long resultingVersion) {
    public ManagementAuditOutcome {
        Objects.requireNonNull(outcome, "outcome");
        code = ManagementModelValidation.boundedText(code, "code", 1, 64, false);
        if (!code.matches("[A-Z][A-Z0-9_]*")) {
            throw new IllegalArgumentException("code must be bounded upper snake case");
        }
        if (resultingVersion != null) {
            ManagementModelValidation.nonNegativeVersion(resultingVersion, "resultingVersion");
            if (outcome != ManagementAuditTerminalOutcome.SUCCEEDED) {
                throw new IllegalArgumentException("only a succeeded outcome may carry a resulting version");
            }
        }
    }
}
