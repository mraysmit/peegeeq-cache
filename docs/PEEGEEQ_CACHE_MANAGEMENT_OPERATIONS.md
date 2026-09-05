# PeeGeeQ Cache management server operations

The management server is a security-sensitive operational component. It owns database credentials, a durable audit journal, browser sessions, PostgreSQL pools, live streams, and mandatory Prometheus telemetry. Defaults bind only to loopback and startup fails if the audit fingerprint key is absent or shorter than 32 UTF-8 bytes.

## Build and artifact

Build and verify with OpenJDK 26.0.2 while retaining Java 21 bytecode compatibility:

```powershell
$env:JAVA_HOME = 'C:\Users\mraysmit\.jdks\openjdk-26.0.2'
mvn -pl peegee-cache-rest -am verify
```

The executable is `peegee-cache-rest/target/peegee-cache-rest-0.1.0-SNAPSHOT-runnable.jar`. Verification covers its manifest, Java 21 class-file version, OpenAPI at `openapi/peegeeq-cache-management-v1.yaml`, fallback page at `ui/index.html`, single SLF4J provider, exclusion of test fixtures, startup, readiness, Prometheus scrape, and startup log.

The U11 implementation acceptance completed on 5 September 2026: Phase 8.3 U0-U11 is complete, the packaged browser gate passed 557/557 reportable scenarios plus all three then-existing infrastructure checks, and the full 11-module reactor passed on PostgreSQL 15.17, 16.13, 17.11, and 18.3. Subsequent P7 screenshot acceptance passed a fresh PostgreSQL 18.3 reactor with 563 browser/infrastructure tests, all 557 scenarios captured, and 1,122 PNGs embedded in the portable report. The owning [Playwright plan](design/PEEGEEQ_CACHE_PLAYWRIGHT_IMPLEMENTATION_PLAN.md) records exact evidence and distinguishes that fresh run from the earlier four-version baseline.

## Startup configuration

The executable defaults to fail-closed local-token mode and also supports explicit trusted-proxy mode. Set its audit key through the process secret store, never in a command-line argument or committed file.

| Environment variable | Default | Purpose |
|---|---|---|
| `PEEGEEQ_MANAGEMENT_AUDIT_KEY` | required | At least 32 UTF-8 bytes for stable HMAC audit fingerprints |
| `PEEGEEQ_MANAGEMENT_AUTHENTICATION_MODE` | `LOCAL_TOKEN` | `LOCAL_TOKEN` or `TRUSTED_PROXY` |
| `PEEGEEQ_MANAGEMENT_BIND_ADDRESS` | `127.0.0.1` | Literal listen address; local-token mode requires loopback |
| `PEEGEEQ_MANAGEMENT_PORT` | `8080` | HTTP port |
| `PEEGEEQ_MANAGEMENT_SERVER_ORIGIN` | derived from bind/port | Exact browser origin for origin and CSRF enforcement |
| `PEEGEEQ_MANAGEMENT_AUDIT_JOURNAL` | `logs/management-audit.jsonl` | Append-only, fsync-backed audit journal |
| `PEEGEEQ_MANAGEMENT_TARGET_DNS_SUFFIXES` | `internal` | Comma-separated PostgreSQL DNS suffix allowlist |
| `PEEGEEQ_MANAGEMENT_TARGET_CIDRS` | `10.0.0.0/8` | Comma-separated CIDR allowlist; every DNS answer must match |
| `PEEGEEQ_MANAGEMENT_TARGET_PORTS` | `5432` | Comma-separated PostgreSQL ports |
| `PEEGEEQ_MANAGEMENT_TARGET_ADDRESS_CLASSES` | `PRIVATE` | Comma-separated `PRIVATE`, `LOOPBACK`, `LINK_LOCAL`, or `PUBLIC` |
| `PEEGEEQ_MANAGEMENT_TRUST_PROFILE_ID` | `default` | Server-owned trust-profile identifier |
| `PEEGEEQ_MANAGEMENT_TRUST_PEM` | required on setup connection | PEM CA bundle for the trust profile |

Trusted-proxy mode additionally requires explicit network and HTTPS-origin policy:

| Environment variable | Default | Purpose |
|---|---|---|
| `PEEGEEQ_MANAGEMENT_TRUSTED_PROXY_CIDRS` | required | Comma-separated immediate-peer CIDRs allowed to assert identity |
| `PEEGEEQ_MANAGEMENT_ALLOWED_ORIGINS` | required | Comma-separated HTTPS browser origins allowed credentialed access |
| `PEEGEEQ_MANAGEMENT_TRUSTED_PROXY_USER_HEADER` | `X-PeeGeeQ-User` | Proxy-owned actor header |
| `PEEGEEQ_MANAGEMENT_TRUSTED_PROXY_ROLES_HEADER` | `X-PeeGeeQ-Roles` | Proxy-owned comma-separated roles header |
| `PEEGEEQ_MANAGEMENT_TRUSTED_PROXY_ALLOWED_ROLES` | `viewer,operator` | Normalized roles the server accepts from the proxy |

In trusted-proxy mode, `PEEGEEQ_MANAGEMENT_SERVER_ORIGIN` is required and must be HTTPS. CIDRs and allowed origins have no permissive defaults.

Configured setup secrets use `env:VARIABLE_NAME` references. The server resolves the named environment value; setup request bodies never contain server filesystem paths or credentials.

Linux/macOS:

```shell
export PEEGEEQ_MANAGEMENT_AUDIT_KEY="$(openssl rand -base64 32)"
export PEEGEEQ_MANAGEMENT_TRUST_PEM=/etc/peegeeq/database-ca.pem
java -jar peegee-cache-rest/target/peegee-cache-rest-0.1.0-SNAPSHOT-runnable.jar
```

PowerShell:

```powershell
$env:PEEGEEQ_MANAGEMENT_AUDIT_KEY = '<secret-store-value-at-least-32-bytes>'
$env:PEEGEEQ_MANAGEMENT_TRUST_PEM = 'C:\ProgramData\PeeGeeQ\database-ca.pem'
& "$env:JAVA_HOME\bin\java.exe" -jar .\peegee-cache-rest\target\peegee-cache-rest-0.1.0-SNAPSHOT-runnable.jar
```

In local-token mode, the controlling process receives `PEEGEEQ_MANAGEMENT_BOOTSTRAP_TOKEN=...` once on standard output. Capture it into protected process state, exchange it immediately, and do not redirect standard output to a shared log collector. Trusted-proxy mode emits no bootstrap token. Ordinary server logs use the packaged SLF4J provider and do not contain credentials.

## Local authentication

1. Start on loopback and capture the one-time bootstrap token.
2. From the exact configured origin, `POST /api/v1/session/local` with JSON `{"token":"..."}`.
3. Keep the returned `PGQMGMTSESSION` HttpOnly, SameSite=Strict cookie and response `csrfToken` only in browser memory.
4. Send the cookie on protected reads. Send the cookie, exact `Origin`, and `X-PeeGeeQ-CSRF` on every state change.
5. `DELETE /api/v1/session/local` logs out. Restarting invalidates all sessions and issues a new bootstrap token.

Never put bootstrap/session/CSRF values in URLs, browser storage, source files, screenshots, logs, or telemetry labels.

Development browser evidence is captured directly, without screenshot masking, redaction, or
replacement values. The operational handling rule above does not authorize altering those captures.
They show the actual application's password controls and reveal/hide state and remain local generated
artifacts. See the [screenshot capture rules](guidelines/PEEGEEQ_CACHE_TEST_COVERAGE_AND_TDD_APPROACH.md#browser-screenshot-evidence).

## Trusted-proxy authentication

Terminate browser TLS and external identity at a hardened reverse proxy. Configure the management server with `TRUSTED_PROXY`, bind it only on the private backend interface, and include only the proxy's immediate-peer networks in `TRUSTED_PROXY_CIDRS`. The proxy must remove any client-supplied identity headers before setting its authoritative user and role headers.

The browser calls `GET /api/v1/session` through the HTTPS proxy. The server accepts identity headers only from a trusted immediate peer, validates the exact HTTPS origin, and returns a Secure, HttpOnly, SameSite=Strict session cookie plus an in-memory CSRF token. Identity is revalidated and bound to the session on every protected request; actor or role changes rotate the session. Do not expose the backend listener directly or forward untrusted `X-PeeGeeQ-*` values.

Example server-side environment:

```shell
export PEEGEEQ_MANAGEMENT_AUTHENTICATION_MODE=TRUSTED_PROXY
export PEEGEEQ_MANAGEMENT_BIND_ADDRESS=10.40.0.12
export PEEGEEQ_MANAGEMENT_SERVER_ORIGIN=https://management.internal.example
export PEEGEEQ_MANAGEMENT_TRUSTED_PROXY_CIDRS=10.40.0.0/24
export PEEGEEQ_MANAGEMENT_ALLOWED_ORIGINS=https://console.internal.example
```

## PostgreSQL TLS and target policy

Every setup uses `VERIFY_FULL`, chain validation, hostname verification, policy-controlled DNS resolution, and address pinning. Reconnect repeats resolution and policy validation. Requests supply only a configured trust-profile identifier, never a trust path.

Before allowing a target, confirm its hostname suffix, port, every DNS answer, address class, CIDR, and trust profile. Keep `PUBLIC`, `LINK_LOCAL`, and `LOOPBACK` disabled unless explicitly required. Defaults allow private `10.0.0.0/8` hosts ending in `internal` on port 5432.

Rotate a database CA by atomically replacing the server-owned PEM, restarting, and reconnecting setups. Existing runtimes retain trust material loaded when their pools were created.

## Secret and audit-key rotation

Rotate a database secret in the external store, then reconnect its setup. Old runtime secret arrays are erased during cleanup.

Audit-key rotation changes subsequent fingerprints. Preserve the old key according to policy if historical correlation is required, record the rotation boundary outside the journal, replace `PEEGEEQ_MANAGEMENT_AUDIT_KEY`, and restart. Never rewrite historical audit records or copy keys into journal metadata.

## Audit backup and recovery

The JSON-lines journal is append-only and fsync-backed. Privileged work reserves an intent before database work and persists its outcome afterward. Readiness goes down when mutation audit is unavailable. Startup converts an incomplete durable intent to `UNKNOWN` before accepting mutations.

For a consistent backup, stop cleanly and copy the journal, or take a filesystem snapshot preserving the exact file boundary. Encrypt backups, restrict access, and retain them under the deployment audit policy. Restore before startup. Never truncate the live file, edit/merge lines, or run two processes against one journal path.

Monitor audit pending/capacity/readiness gauges, rejected reservations, recovered intents, and persistence failures. Treat `AUDIT_OUTCOME_UNAVAILABLE` as an uncertain mutation: reload authoritative state and restore audit readiness before retrying.

## Prometheus and readiness

`GET /health/ready` returns 200 only after required resources are ready. `GET /metrics` exposes mandatory Prometheus telemetry on the same listener. Scraping does not require a browser session, so keep the listener on loopback or enforce access at the sidecar, reverse-proxy, or firewall boundary.

Signals include:

- HTTP active requests, totals, and duration by bounded route, method, surface, status, and safe error code;
- authentication, authorization, CSRF, origin, target-policy, and rate-limit outcomes without identity/path labels;
- audit pressure, recovery, persistence failure, and mutation readiness;
- lifecycle/readiness, registered setups, active pools, subscriptions, streams, retained bytes, evictions, resets, and shutdown leaks;
- PostgreSQL-backed management operation totals and duration by bounded operation and outcome.

Alert on readiness down, audit persistence failure or saturation, rejected audit reservations, unexpected security rejection growth, repeated stream resets, retained-byte saturation, and nonzero shutdown leaked resources.

## Shutdown

Send the normal service-manager stop signal and allow at least 30 seconds. Shutdown marks readiness down, closes HTTP/live connections, cancels setup samplers and timers, closes setup pools/runtimes, drains and fsyncs audit, erases sessions, and closes Vert.x. Forced termination can leave an intent that startup must recover as `UNKNOWN`.

## Troubleshooting

| Symptom | Checks and action |
|---|---|
| Audit key rejected | Confirm the secret exists and is at least 32 UTF-8 bytes; do not print it |
| Bind failure | Confirm literal loopback address, free port, and matching server origin |
| Readiness 503 | Inspect audit readiness/persistence metrics and lifecycle logs before mutating |
| `TARGET_FORBIDDEN` | Check suffix, CIDR, address class, port, every DNS answer, and trust profile |
| TLS failure | Verify readable PEM, issuing CA chain, and requested hostname; `VERIFY_FULL` is mandatory |
| Repeated SSE reset | Check five-minute retention, `Last-Event-ID`, evictions, reconnects, and restarts |
| Empty/unreachable scrape | Verify `/metrics`, listener/network policy, and packaged Prometheus registry |
| SLF4J provider warning | Run the verified runnable classifier unchanged; it contains exactly the simple provider |

Use correlation IDs and bounded structured event names. Never enable raw request/body logging or add setup IDs, keys, namespaces, actors, tokens, payloads, passwords, or fingerprints as metric labels.
