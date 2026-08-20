# PeeGeeQ Cache Management Build and Configuration Decision

**Status:** Accepted for Phase 8.2 M0

**Date:** 20 August 2026

## Decision

The root Maven reactor remains the only release build entry point. Phase M1 adds two sibling child modules in dependency order:

1. `peegee-cache-management-ui`
2. `peegee-cache-rest`

`peegee-cache-management-ui` owns Node, npm, TypeScript, Vite, generated-client validation, and the compiled webroot. It publishes the webroot as a Maven artifact. `peegee-cache-rest` consumes that artifact during `package`; it never writes generated files into the UI module or copies output into another module's `src` tree. Until the separately authorized Phase 8.3 implementation begins, the UI module produces a deterministic empty webroot artifact and contract-validation metadata only.

`peegee-cache-rest` depends on `peegee-cache-api`, `peegee-cache-runtime`, and `peegee-cache-observability`. Test scope may depend on `peegee-cache-test-support`. No cache library module depends on REST or UI code. The resulting direction is:

```text
peegee-cache-management-ui artifact
                 │
                 ▼
peegee-cache-rest ──► peegee-cache-runtime ──► peegee-cache-pg
          │                    │                       │
          ├──► peegee-cache-observability             ▼
          └──► peegee-cache-api ◄──────────── peegee-cache-core
```

The root lifecycle is reproducible:

- `validate` validates Java/Maven versions, the OpenAPI and route manifest once they exist, and frontend tool declarations;
- `test` runs Java and frontend unit/contract tests;
- `verify` runs backend protocol acceptance and the backend-owned non-production browser harness;
- `package` embeds the UI artifact in the runnable REST artifact at `webroot/ui`;
- Phase 8.3 may add production UI tests and assets without changing the dependency direction or root entry point.

M0 records this topology only. Empty module POMs and the OpenAPI file belong to M1, so no management implementation class or premature route is introduced by this decision.

## REST configuration shape

Configuration is immutable and supplied by the runnable REST boundary. Library defaults do not silently enable a management server. The planned records are:

```java
record ManagementServerConfig(
    String bindHost,
    int port,
    AuthenticationConfig authentication,
    SessionConfig sessions,
    SetupTargetPolicyConfig targetPolicy,
    Map<String, TlsTrustProfileConfig> trustProfiles,
    ManagementLimitsConfig limits,
    AuditConfig audit,
    List<ConfiguredSetupReference> configuredSetups
) {}

sealed interface AuthenticationConfig
    permits LocalTokenAuthenticationConfig, TrustedProxyAuthenticationConfig {}

record LocalTokenAuthenticationConfig(SecretOutputReference bootstrapTokenOutput) {}

record TrustedProxyAuthenticationConfig(
    List<String> trustedProxyCidrs,
    String userHeader,
    String rolesHeader,
    Set<String> allowedRoles,
    List<String> allowedHttpsOrigins
) {}

record SessionConfig(Duration idleTimeout, Duration absoluteTimeout) {}

record SetupTargetPolicyConfig(
    List<String> allowedDnsSuffixes,
    List<String> allowedCidrs,
    Set<Integer> allowedPorts,
    boolean allowLoopback,
    boolean allowPrivate,
    boolean allowLinkLocal,
    boolean allowPublic
) {}

record TlsTrustProfileConfig(String trustProfileId, SecretReference trustMaterial) {}

record ManagementLimitsConfig(
    int ordinaryRequestBytes,
    int setupRequestBytes,
    int maximumValueBytes,
    int subscriptionsPerActor,
    int subscriptionsPerSetup,
    int subscriptionsPerProcess,
    long retainedPayloadBytesPerSubscription,
    long retainedPayloadBytesPerProcess
) {}

record AuditConfig(
    SecretReference fingerprintKey,
    String fingerprintKeyId,
    SecretReference durableJournal,
    int reservationCapacity
) {}

record ConfiguredSetupReference(
    String setupId,
    String displayName,
    SecretReference connectionSecret,
    String trustProfileId
) {}
```

The exact Java package and serialization adapter are M6 implementation choices; the security invariants are fixed now.

## Secret references

`SecretReference` is an opaque server-side locator, never a secret value and never browser-supplied. Supported reference kinds are explicitly allowlisted by the runnable server, initially environment-variable and owner-readable-file references. A request cannot provide a filesystem path, trust store, audit key, client private key, or arbitrary provider URI.

`SecretOutputReference` is similarly server-configured and identifies the controlling terminal or an owner-readable file used once for local bootstrap-token delivery. The token bypasses logging and is never returned by configuration inspection.

Secret reference objects:

- are excluded from setup summaries, health, capabilities, events, errors, telemetry, and ordinary logs;
- are resolved only by the owning server component at point of use;
- never expose a `toString()` containing resolved material;
- are cleared or closed with the setup/server lifecycle where the provider returns mutable secret material;
- cannot be submitted or overridden through REST.

## Validation decisions

- Exactly one authentication mode is required.
- `LOCAL_TOKEN` requires a loopback bind and same-origin UI; it rejects configured CORS origins.
- Trusted-proxy mode requires at least one trusted proxy CIDR and a bounded role allowlist. Cross-origin access, when enabled, uses exact HTTPS origins only.
- Session idle and absolute timeouts are positive; absolute timeout is at most 24 hours and not shorter than idle timeout.
- A non-empty outbound target policy is required before UI-session setup registration can be enabled. Link-local and cloud-metadata destinations are denied by default.
- Trust profiles are addressed only by server-known identifiers.
- Audit journal and fingerprint-key references are mandatory. Mutation readiness stays down until incomplete intents have recovered and the sink can reserve terminal-outcome capacity.
- Request, value, subscription, and retained-byte limits are positive and bounded by implementation maxima documented in the API contract.
- No environment-specific host, credential, token, trust path, or audit key is embedded in library code or committed configuration.

## Rejected alternatives

- Writing Vite output directly into `peegee-cache-rest/src/main/resources` was rejected because it mutates another module's source tree and makes clean/reproducible builds harder to audit.
- Invoking an untracked external frontend build outside Maven was rejected because it creates a second release entry point.
- Making REST or UI dependencies flow into cache library modules was rejected because it would couple the embedded library to a management deployment.
- Adding placeholder management implementation classes in M0 was rejected because M1 and M2 require deliberate red tests before executable behavior.
