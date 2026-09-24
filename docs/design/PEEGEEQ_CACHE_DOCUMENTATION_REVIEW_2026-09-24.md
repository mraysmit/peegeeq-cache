# PeeGeeQ Cache — Documentation Review (24 September 2026)

**Scope:** every Markdown file under `docs/` plus `CLAUDE.md` and `README.md` on the `peegeeq-cache` checkout at `C:\Users\mraysmit\dev\idea-projects\peegeeq-cache` (HEAD `3ed1162`, 15 September 2026). 19 current documents (about 13,800 lines) were read in full; the 11 files under `docs/design/archive/` were read for their banners and for anything current documents still lean on. `git status` reported 346 modified files in the working tree; whether those differences are only line endings or file modes was not established, so line numbers refer to the working-tree files as reviewed.

**Method:** every document was checked against the others and against the code. Where a finding depends on the code, the code was inspected and the result is marked **[verified]**; where the finding is purely documentary (one document contradicting another, or itself) it is marked **[doc]**. Line numbers refer to the files as reviewed. Nothing in this review is inferred from git history alone.

---

## 1. Summary

The documentation set has four layers that were last reconciled at different moments, and the seams show. The guidelines (10 September) and the benchmark documents (15 September) are the most current. The four management documents (API, UI design, coverage matrix, management operations) received a "capability gating removed (10 September 2026)" banner on 11 September, but their bodies were not brought into line with it: they still deny features that exist, describe a registration request that has since grown, and quote counts (60 operations, 557 scenarios, 30 management methods, an "11-module reactor") that the code contradicts (59, 539, 29, 10 modules plus the parent). The core design document carries a March 2026 header and a seven-module tree, and its native-SQL and bootstrap sections are contradicted by the shipped `V001` baseline and by the newer `PEEGEEQ_CACHE_NATIVE_SQL_API.md`.

The single most damaging document is `docs/guidelines/PEEGEEQ_CACHE_DEV_GUIDELINES.md`. `CLAUDE.md` names it first as mandatory reading, yet nearly all of its 4,402 lines are imported from the sibling PeeGeeQ queue repository: its index lists six files that do not exist in this repository, it prescribes a `-Pintegration-tests` Maven profile that does not exist here, it endorses mocking, blanket `-DskipTests` bans and silent `onFailure` swallowing that `CLAUDE.md` forbids, and it names a PostgreSQL image (`15.13`) that the test support module does not use (`18.3-alpine`). An engineer or agent that follows `CLAUDE.md` literally receives contradictory instructions from its first two required documents.

Counts and status claims are the recurring defect. Six different documents state scenario, operation or method counts in their own words, and at least three different values are in circulation for each. The coverage matrix is the only document whose tables reconcile with the OpenAPI file; its prose does not.

The archive is well handled: all ten archived Markdown files carry an "Archived 11 September 2026" banner. The remaining problem is that current documents still cite four of them as the "authoritative execution sequence", the "reviewed exact route inventory", or the owner of gate evidence.

Overall verdicts, one line per file, are in §7. Recommended actions are in §8; they are ordered so that the first four remove the contradictions an agent would hit on day one.

---

## 2. Findings that are wrong against the code [verified]

These were checked against the checkout on 24 September 2026. Each row states what the document says, what the code says, and where.

| # | Document and line | Document says | Code says |
|---|---|---|---|
| V1 | `docs/guidelines/PEEGEEQ_CACHE_DEV_GUIDELINES.md` L7, L26–L36, L3846–L3847 | "This document consolidates all markdown files currently in docs/guidelines"; index lists `pgq-coding-principles.md`, `VERTX_MULTI-STATEMENT_SQL_BUG_ANALYSIS.md`, `Vertx-5x-Patterns-Guide.md`, `MAVEN_TOOLCHAINS_EXPLAINED.md`, `PEEGEEQ_CRASH_RECOVERY_GUIDE.md`, `SHUTDOWN_AND_LIFECYCLE_FIXES.md` as files in "This folder" | `docs/guidelines/` contains exactly three files: this one, `PEEGEEQ_CACHE_TEST_COMMANDS.md`, `PEEGEEQ_CACHE_TEST_COVERAGE_AND_TDD_APPROACH.md`. None of the six exists anywhere in the repository. |
| V2 | `DEV_GUIDELINES` L466–L470, L514–L528 | "✅ CORRECT: `mvn test -Pintegration-tests`"; "❌ WRONG: `mvn test`" | No `integration-tests` profile exists in any pom. The root profiles are `release-artifacts` and `central-release` (the benchmark module adds `benchmark`, `benchmark-capture`, `benchmark-calibration` and `benchmark-characterisation`; the REST module adds `playwright-observe`). `TEST_COMMANDS.md` L42–L55 correctly uses the plain `mvn test -pl … -Dtest=` form this document labels WRONG. |
| V3 | `DEV_GUIDELINES` L2343, L2740; also L4242–L4260 (toolchains), L523 (`jacoco:report`) | "standardized container image: postgres:15.13-alpine3.20"; Maven toolchains; JaCoCo | `peegee-cache-test-support` defaults to `postgres:18.3-alpine` (as `TDD_APPROACH.md` L63 says). Root pom has no `maven-toolchains-plugin` and no `jacoco` plugin. |
| V4 | `README.md` L105 | link `docs/PEEGEEQ_CACHE_NATIVE_SQL_API.md` | File is at `docs/design/PEEGEEQ_CACHE_NATIVE_SQL_API.md`. Broken link. (`PEEGEEQ_CACHE_OPERATIONS.md` L43 links it correctly.) |
| V5 | `README.md` L14–L16 and L54; `PEEGEEQ_CACHE_OPERATIONS.md` L73; `JENKINS_CI_SETUP.md` L23–L24 | README attributes CI to the Jenkinsfile; OPERATIONS says `.github/workflows/postgresql-compatibility.yml` runs the four-version matrix on every PR and master push; JENKINS says the job "has no automatic trigger" | Both are true and neither document says so: `.github/workflows/postgresql-compatibility.yml` exists with `on: push: branches: [master]` and `pull_request`, and the Jenkins job is manual. No current document states that the repository has two CI systems or which evidence comes from which. |
| V6 | `docs/design/PEEGEEQ_CACHE_MANAGEMENT_API.md` L1115 "The management API does not expose lock acquire or renew in V1."; L1587 "V1 intentionally has no API for … lock acquisition/renewal"; `PEEGEEQ_CACHE_MANAGEMENT_UI_DESIGN.md` L383 "The console does not acquire or renew application locks in V1.", L873 (out of scope) | Locks cannot be acquired/renewed from the API or console | `peegeeq-cache-management-v1.yaml` declares `acquireLock`, `renewLock`, `releaseLock`, `checkLockOwnership`; `src/features/advanced/AdvancedOperationsPage.tsx` exposes them; the shell routes `/advanced`. The coverage matrix (LOCK-01..04) records them as COMPLETE. |
| V7 | `MANAGEMENT_UI_DESIGN.md` §6.1–§6.2 (L186–L218) route/page list | Nine pages; no Advanced operations page | `ManagementShell.tsx` routes `/`, `/setups`, `/namespaces`, `/namespaces/:encodedNamespace`, `/keys`, `/keys/:encodedNamespace/:encodedKey`, `/counters`, `/locks`, `/pubsub`, `/monitoring`, `/advanced`, `/settings`. The word "Advanced" does not appear in the UI design document. |
| V8 | `MANAGEMENT_UI_DESIGN.md` L54 | `src/components/common` "may only compose … `StatCard`, `SetupScopeBar`, `FilterBar`, `ConfirmDialog`, `ConnectionStatus`, `ErrorBoundary`" | `src/components/common/` contains `ConnectionStatus.tsx`, `SetupScopeBar.tsx`, `StatCard.tsx`, `ValueSelect.tsx`. `FilterBar` and `ConfirmDialog` were deleted in U11.9; `ErrorBoundary` does not exist; `ValueSelect` (which the Java Playwright locators depend on) is not listed. |
| V9 | `MANAGEMENT_UI_DESIGN.md` L532 "REST/static server: `127.0.0.1:8089`", L538 Vite proxies to 8089; `PEEGEEQ_CACHE_MANAGEMENT_OPERATIONS.md` L27 `PEEGEEQ_MANAGEMENT_PORT` default `8080` | 8089 vs 8080 | `ManagementServerMain.java` L75 defaults the port to **8080**; `vite.config` proxies `/api` and `/ws` to **8089**. Both documents are "right" about their own file, and a developer following the UI design's dev-mode section against a default server gets a proxy that points at nothing. This is a code inconsistency as much as a documentation one. |
| V10 | `MANAGEMENT_UI_DESIGN.md` L482 | API layer "defines tags for `Setup`, `Overview`, `Namespace`, `Entry`, `Counter`, `Lock`, and `Monitoring`" | `tagTypes` are `Setup, Overview, Monitoring, Activity, Namespace, Entry, Counter, Lock, Subscription`. |
| V11 | `MANAGEMENT_API.md` L483–L495 (register request) and L514–L520 (`runtime` block: defaultTtl/sweeper/poolMaxSize only); `MANAGEMENT_UI_DESIGN.md` L271 registration form "host, port, database, username, password, schema, SSL mode, and pool limits" | Registration carries connection fields only | `SetupRegistrationRequest` in the OpenAPI file has a `runtime` object with `pubSubEnabled`, `pubSubChannelPrefix`, `telemetryMode`, `writeBehind` (L2051). The coverage matrix CONFIG-03..08 already describes this; the API and UI documents do not. |
| V12 | `MANAGEMENT_API.md` L1634 "all 60 OpenAPI management operations", "exactly 557 desktop Java Playwright scenarios", "30-method management surface"; `FUNCTIONALITY_COVERAGE_MATRIX.md` L13, L204 "60-operation", L204 "all 30 `ManagementService` methods"; `PEEGEEQ_CACHE_MANAGEMENT_OPERATIONS.md` L16 "557/557" | 60 / 557 / 30 | OpenAPI has **59** `operationId`s (none under a `capabilities` path); `peegeeq.playwright.expectedScenarios` and `CURRENT_SCENARIO_COUNT` are both **539**; `ManagementService` declares **29** methods. The matrix's own L62 and L64 already say 59 and 29 and 539. |
| V13 | `MANAGEMENT_API.md` L1134 "The effective maximum is returned by the setup capabilities endpoint." | A capabilities endpoint exists | No path in the OpenAPI file contains `capabilities`; no `SetupCapabilities`/`AdminCapabilities`/`ManagementCapability` symbol remains in any module's `src/main`. Same for `MANAGEMENT_UI_DESIGN.md` L595–L597 ("Every feature flag is derived from the connected runtime …", "The UI hides unavailable navigation destinations"), which its own L675 ("there is no capability negotiation") contradicts. |
| V14 | `docs/design/PEEGEEQ_CACHE_DESIGN.md` L1074–L1083 module tree (7 modules); L2910 | api, core, pg, runtime, observability, test-support, examples | Reactor has 10 modules: the seven plus `peegee-cache-management-ui`, `peegee-cache-rest`, `peegee-cache-benchmarks`. The REST server, the management UI, OpenAPI and the browser gate are not mentioned anywhere in the 3,306-line design document; L357/L442 still say a server "can come later". |
| V15 | `DESIGN.md` §16 L1467 `increment_counter(… ttl_millis, ttl_mode)` (5 params), L1470 `acquire_lock(… lease_ttl_millis, issue_fencing_token)` (5 params); L1461–L1474 lists `get_entry`, `expire_entry`, `persist_entry`, `publish` functions; L1477 "These do not need to be implemented before the Java API exists" | Native SQL function set and arities | `db/bootstrap/V001__create_peegee_cache_schema.sql` defines `acquire_lock(p_namespace, p_lock_key, p_owner_token, p_lease_ttl_millis, p_reentrant DEFAULT FALSE, p_issue_fencing_token DEFAULT TRUE)` (6), `increment_counter(p_namespace, p_counter_key, p_delta, p_ttl_millis DEFAULT NULL, p_ttl_mode DEFAULT 'PRESERVE_EXISTING', p_create_if_missing DEFAULT TRUE)` (6), plus `renew_lock`, `release_lock`, `set_counter`, `delete_counter`, `set_entry`, `delete_entry`. No `get_entry`/`expire_entry`/`persist_entry`/`publish` functions. `NATIVE_SQL_API.md` matches the code; `DESIGN.md` §16 does not and does not reference it. |
| V16 | `DESIGN.md` §28 L2981–L2988 "V001__create_schema.sql … V006__create_indexes.sql"; §15 lists three tables and no views | Six migration files | One file, `V001__create_peegee_cache_schema.sql`, containing tables, functions and the views `live_entries`, `live_counters`, `active_locks`, which `NATIVE_SQL_API.md` L33–L35 documents as the read contract and `DESIGN.md` never names. |
| V17 | `DESIGN.md` §19 L2218–L2239, L2272–L2276 | `PeeGeeCacheBootstrapOptions` has three fields; no `defaults()` on config records; no `SchemaBootstrapMode` | `PeeGeeCacheConfig.defaults()`, `PgCacheStoreConfig.defaults()` and `enum SchemaBootstrapMode` all exist (README L69–L70, L105 describe them correctly). `BootstrapSqlRenderer` has both `loadBootstrapSql()` (README) and `loadForSchema`/`loadMigrationForSchema` (NATIVE_SQL_API) — both documents are right; DESIGN mentions neither. |
| V18 | `DESIGN.md` §20 L2300–L2304 `MetricsRecorder.recordGet(String namespace, boolean hit)` | Telemetry SPI takes the namespace | The shipped SPI is `peegee-cache-core/.../telemetry/CacheTelemetry.java`; there is no `MetricsRecorder`. `PEEGEEQ_CACHE_OPERATIONS.md` L22 states the actual rule (no user-controlled keys or namespaces in metric attributes). |
| V19 | `DESIGN.md` §12a.11 L1028–L1030 "Strict TDD order for V2 implementation. Each step must pass before the next begins" (six to-do steps); §29 L2994–L3015 "Recommended next steps … 4. Actual Java module skeleton … Immediate next move: Define the API module first" | Write-behind and the module skeleton are still to do | `WriteBehindBuffer`, `WriteBehindConfig`, `WriteBehindCacheService` exist; ten modules exist; ~169 Java test classes and 31 Vitest files exist. Both sections are plans for work that shipped. |
| V20 | `docs/PEEGEEQ_CACHE_LOGGING.md` L7, L30 | `slf4j-simple` "is limited to tests and the standalone example and benchmark processes" | `peegee-cache-rest/pom.xml` L96–L97 declares `slf4j-simple` at `runtime` scope for the management server's runnable jar (which `MANAGEMENT_OPERATIONS.md` L14/L151 describe). The logging standard predates the REST module (mtime 17 August) and does not cover it or the UI. |
| V21 | `docs/PEEGEEQ_CACHE_RELEASE_PACKAGING.md` L3–L12 | "The reactor produces these library artifacts" — six libraries plus examples and benchmarks (8 modules) | 10 modules. Whether `peegee-cache-rest` and `peegee-cache-management-ui` are published to Central, excluded, or would fail Central validation is not stated anywhere. |
| V22 | `docs/guidelines/PEEGEEQ_CACHE_TEST_COMMANDS.md` L18 "`peegee-cache-test-support` … No tests"; L24 "`peegee-cache-benchmarks`, `peegee-cache-examples` — Small unit tests — Docker: No"; `CLAUDE.md` L15 Docker-first list omits benchmarks | Test-support has no tests; benchmarks are small unit tests needing no Docker | `peegee-cache-test-support` has 3 test classes; `peegee-cache-benchmarks` has **46** test classes including real-PostgreSQL scheduler/scenario/campaign tests (`BENCHMARKS.md` L89 says so itself); `peegee-cache-examples` has none. |
| V23 | `docs/guidelines/PEEGEEQ_CACHE_TEST_COVERAGE_AND_TDD_APPROACH.md` L79–L92 "Current test inventory … Last verified: 2026-08-17 … Total 286"; L91 benchmarks "13" | Current inventory | Omits `peegee-cache-rest` (47 test classes + 27 `*IT`), the UI module (31 test files + 5 guards), and undercounts benchmarks (46). Presented as "Current". |
| V24 | `docs/design/PEEGEEQ_CACHE_JENKINS_CI_SETUP.md` L124–L125 "managed Vert.x execution adapter and campaign runner remain unfinished"; L194 JSON campaign stage "remains explicitly pending" | Characterisation framework unfinished | Commit `f983355` (13 September) "complete characterisation framework and campaign reporting"; `BENCHMARKS.md` L8 and `PRODUCTION_BENCHMARK_PLAN.md` §8 say complete. **However** the Jenkinsfile's `benchmark-characterisation` run mode (L251–L297) still invokes `-Pbenchmark-capture` (the legacy HTML capture), so the migration this document calls pending genuinely has not happened, and no current document tracks it (the plan raised it at L1015–L1016 and never closed it). |
| V25 | `docs/PEEGEEQ_CACHE_BENCHMARKS.md` L106 `-Pbenchmark-characterisation` (Maven profile, JSON campaign); `JENKINS_CI_SETUP.md` L85 and `PRODUCTION_BENCHMARK_PLAN.md` L1042–L1043 `benchmark-characterisation` (Jenkins RUN_MODE, legacy capture) | Same identifier | Both exist and do different things: the Maven profile `benchmark-characterisation` runs the JSON campaign; the Jenkins `RUN_MODE` choice `benchmark-characterisation` runs `-Pbenchmark-capture`. No document flags the collision. |
| V26 | `PRODUCTION_BENCHMARK_PLAN.md` — 110 evidence references, all under `target/…` (e.g. L369–L370, L862, L1065, L1450) | Evidence retained | `logs/` contains no benchmark log; `target/` is ignored and wiped by `mvn clean`. `TEST_COMMANDS.md` L4 and `CLAUDE.md` require `logs\<description>-<YYYYMMDD>.log`. Every September verification claim in the plan is unverifiable from the repository. |
| V27 | `PRODUCTION_BENCHMARK_PLAN.md` L1457 "all eleven modules succeeded in 6:07 … (881 total)"; `BENCHMARKS.md` L96 "definitive full reactor gate recorded 881 tests" | A full reactor gate in 6 minutes | `TEST_COMMANDS.md` L6 puts the complete `verify` at 20–35 minutes because of the 539-scenario browser catalogue. A 6:07 run cannot have included it; the Maven phase is not stated. "Eleven modules" is the Maven reactor summary count (ten modules plus the parent pom) — acceptable, but four documents say "11-module reactor" without saying that. |
| V28 | `PEEGEEQ_CACHE_MANAGEMENT_OPERATIONS.md` L7, L10 "Build and verify with OpenJDK 26.0.2"; `$env:JAVA_HOME = 'C:\Users\mraysmit\.jdks\openjdk-26.0.2'` | JDK 26 required | Enforcer range is `[21,27)`, compiler release 21. JDK 26 is one valid choice, not a requirement; the path is one workstation's. (`/health/ready`, `/metrics`, `PEEGEEQ_MANAGEMENT_PORT` default 8080, `sslMode` restricted to `VERIFY_FULL` — all verified correct in this document.) |
| V29 | `DEV_GUIDELINES.md` L605, L712, L736, L1101 Vert.x "5.0.4" / "the 5.0.4 BOM" | Vert.x 5.0.4 | Root pom `vertx.version` 5.0.8 (README L53 and OPERATIONS L59 are correct). |

---

## 3. Findings that are wrong against other documents or themselves [doc]

### 3.1 Guidelines and rules

`CLAUDE.md` and `TEST_COMMANDS.md` agree with each other and with the code. The problem is `DEV_GUIDELINES.md`, which `CLAUDE.md` L5 makes the first mandatory read:

| Rule | `CLAUDE.md` / `TEST_COMMANDS.md` / `TDD_APPROACH.md` | `DEV_GUIDELINES.md` |
|---|---|---|
| Mocking | "No mocking. No Mockito" (`CLAUDE.md` L16; TDD L65) | L245 "unit tests (which can mock)"; L926/L1292 "Easier to mock individual operations" as a benefit; yet L279 "NO Mockito or Reflection - EVER" |
| `-DskipTests` | Permitted only for the rebuild step (`CLAUDE.md` L12; `TEST_COMMANDS` L86) | L1013 "never skipped with `@Disabled`, `-DskipTests`, or Maven exclusions" |
| Error swallowing | "Every catch block surfaces the error. Silent catches are a defect." (`CLAUDE.md` L17) | L894/L1269 `.onFailure(throwable -> latch.countDown()); // Continue even if close fails` shown as "✅ Modern Style"; L862/L1237 "Continue despite failures" |
| Log reading | "Never use `Select-String` … on the live Maven stream" (`TEST_COMMANDS` L3) | L4388/L4392 `mvn compile -X \| Select-String` |
| Blocking in tests | "no `CompletableFuture.join()` … no raw thread blocking" (TDD L77) | L2365–L2366 `.toCompletionStage().toCompletableFuture().join()` as a testing strategy |
| System properties in libraries | L1524 "Do not read 'fallback' system properties inside libraries" | L1646–L1647 `System.getProperty("peegeeq.database.pool.max-size")` inside a store class; L2153 "All Vert.x 5.x optimizations can be controlled via system properties" |
| Return types | README L127 "All public APIs return `io.vertx.core.Future<T>`"; DEV L453 "Always use composable Futures" | L1475 "All PeeGeeQ producer methods return `CompletableFuture<Void>` … This is intentional" |

Beyond the contradictions, the file is structurally broken: the Vert.x patterns guide appears twice in full (L722–L987 and L1078–L1335, section by section); "Core Principles Summary" appears at L442–L453 and L991–L1002; a code block at L2764–L2768 is an orphan fragment whose opening was lost; "### Key Takeaways" (L2729) is empty and its list appears under a different heading at L2746; L455 reads "Do not reinvent the wheel as you are to do." Roughly 950 lines (L2903–L3853) are a sibling-repository SQL-template postmortem with sprint action items, and L3931–L4237 is an outbox crash-recovery design for a queue product. Every mention of "peegee-cache" in the file (L19, L38, L52, L591, L742, L1087, L2931, L3867, L3940) is a disclaimer that the surrounding text is about the other repository. The header says "Date: March 2026, Version: 0.1"; L3853 says "Next Review: December 15, 2025".

Other guideline-level items: `TDD_APPROACH.md` L333–L334 gives `mvn install -N -q` / `mvn install -pl peegee-cache-api,peegee-cache-core -q` as the pre-`pg` step, using `-q` (banned by `CLAUDE.md` L13), omitting `-am`, `Tee-Object`, and `peegee-cache-test-support`. `TDD_APPROACH.md` L278–L290, the exception for system-property tests, sits under "Browser fixture lifecycle" (L253) rather than under "System property configuration" (L167) and so reads as an exception to the wrong rule. `TEST_COMMANDS.md` L112–L113 splits `node\node.exe` across a line so the instruction reads `ode.exe`. `CLAUDE.md` L11–L20 and `TEST_COMMANDS.md` L6, L84–L88, L104–L106 are the same rules in two places (and `DEV_GUIDELINES` L27 says so); they will drift.

### 3.2 Management documents (API, UI design, coverage matrix, management operations)

The four documents disagree with each other on the points below. Column values are what each document states as current.

| Topic | `MANAGEMENT_API.md` | `MANAGEMENT_UI_DESIGN.md` | `COVERAGE_MATRIX.md` | `MANAGEMENT_OPERATIONS.md` | Code |
|---|---|---|---|---|---|
| Scenario count | 557 (L1634) | 539 (L28) | 539 (L64), 557 (L66, L70, L72 as dated history) | 557 (L16, no banner) | 539 |
| OpenAPI operations | 60 (L1634) | — | 60 (L13, L204) and 59 (L64) | — | 59 |
| `ManagementService` methods | 30 (L1634) | — | 29 (L62), 30 (L204), "62-method" (L235) | — | 29 |
| Infrastructure checks | — | — | "3/3" (L66) and "six" (L72) | "three" (L16) | not checked |
| Lock acquire/renew | not exposed (L1115, L1587) | out of scope (L383, L873) | COMPLETE (L124–L127) | — | exposed |
| Advanced operations page | absent | absent | 17 rows cite it | absent | exists |
| Registration fields | 9 connection fields (L483) | 8 (L271) | + runtime config (L216–L224) | — | + `runtime` object |
| Server port | — | 8089 (L532) | — | 8080 (L27) | 8080 (server), 8089 (Vite proxy) |
| Pool metrics | example gives `active/idle/pending` values (L1261–L1266) but L1288 says they are `UNAVAILABLE` | shown as data (L412) | — | — | not checked |
| Effective limits | on `GET /setups/{id}` as `limits` (L584) but the example at L510–L524 has no `limits` | — | on `getSetupHealth` (L201) | — | not checked |

Additional internal problems: `MANAGEMENT_API.md` L15 calls itself the implementation contract for `peegee-cache-rest`, but §§4–14 specify 48 routes; the ten M11 operations (`checkEntryExists`, `batchGetEntries`, `batchSetEntries`, `batchDeleteEntries`, `scanEntries`, `getCacheMetrics`, `acquireLock`, `renewLock`, `releaseLock`, `checkLockOwnership`) appear only as "ten additional REST/UI operations" at L1634 with no method, path or schema, and the §17 page table has no Advanced operations row. Its status line (L1634, "COMPLETE") is followed by L1645–L1658 "Required implementation order: 1. complete Phase M0 contract closure …", "Before endpoint implementation begins, encode this contract as OpenAPI 3.1". L1687 still speaks of "a minor API capability gate". Header L5–L7 says "17 August 2026, Version 1.0 draft" despite 11 September edits.

`MANAGEMENT_UI_DESIGN.md` L6 "implemented through Phase 8.3 U11" vs L814 "the authoritative execution sequence is the finer-grained U0-U10 strict-TDD plan"; L469 "the U11 phase … corrects this" in the present tense; L17 "requires two new modules" and §14 L816–L847 "Add the REST and UI modules" are plan text in an "implemented" document. L466 lists Playwright in the UI stack while L469 declares an unimported dependency a defect; the UI `package.json` declares neither Playwright nor Axios (Playwright is Java-side in `peegee-cache-rest`).

`FUNCTIONALITY_COVERAGE_MATRIX.md` L143 "Their completeness does not erase the core gaps above" — every core row is COMPLETE. L250 "Documentation gate: implementation plans, operation manifest, API/UI designs, operations guide, and this matrix report the same evidence-backed status" is false today by this review. L42 and L255 still route changes through "the operation manifest", which is archived and whose own banner names the OpenAPI file and inventory tests as the authorities. L5 "COMPLETE — 100% VERIFIED" and L51/L64 carry no date or log reference.

`MANAGEMENT_OPERATIONS.md` has no capability-gating banner and quotes 557 and the 11-module reactor at L16 as the evidence of record; L11 `mvn -pl peegee-cache-rest -am verify` runs the owner-only browser gate with no `Tee-Object` and no warning. It is the only document that documents `/health/ready` and `/metrics`; the API and UI designs omit them.

### 3.3 Core design (`PEEGEEQ_CACHE_DESIGN.md`)

Beyond the code contradictions in §2 (V14–V19), the document has no coherent status: the header is "March 2026 / 0.1" (L11–L13, file last edited 4 September); Part I (L19) is followed by Part III (L1522) with no Part II; sub-section numbering is off by one under every H2 from §15 on ("## 15." contains "### 14.1"–"14.5", "## 18." contains "17.1"–"17.13", and so on through §23), so any cross-reference like "section 16.1" is ambiguous. L965 says `WriteBehindConfig` "is a field in `PeeGeeCacheConfig` (see section 19)" but §19's `PeeGeeCacheConfig` (L2234–L2239) has no such field. L908 says `stopReactive()` must drain and close the pool; L992 and README L58 say the caller-owned pool is never closed by the manager. UNLOGGED tables are recommended (L1256–L1258) and assumed by the write-behind design (L810, L905, L1002, L1025) while L2279 and `OPERATIONS.md` L47 say all tables are logged and no supported path to UNLOGGED exists. The phase scheme ("Phase 8.1" L800/L2244, "Phase 6" L1657, "Phase 2+" L624, "V3" L886) is defined only in the archived implementation plan, which the document does not cite. Appendix A (L3151–L3224) is reviewer commentary; §26 restates `CLAUDE.md`; §27–§29 are plans. The same rationale ("where Redis wins", transactional locality) is written five times (L128–L138, L425–L433, L781–L790, L3216–L3219, L3285–L3289) and `WriteBehindConfig` appears verbatim twice (L951–L962, L2251–L2262).

`NATIVE_SQL_API.md` itself is internally coherent and matches the code; its L7 says function identity includes defaulted parameters but never says which parameters have defaults (the `V001` file does: `p_reentrant`, `p_issue_fencing_token`, `p_ttl_millis`, `p_ttl_mode`, `p_create_if_missing`). Its versioning text (L39–L47) is duplicated in `OPERATIONS.md` L37–L43.

### 3.4 Benchmarks

`BENCHMARKS.md` is the runbook and its status line (L14–L16) is correct; L5–L7 still call the characterisation framework "planned" one line before L8 says it is implemented end to end. The retained campaign (L139–L140: 100 and 200 rps; plan L1435–L1437: 250 ms sampling, GET/SET 4:1) is not reproducible from the documented defaults (L109–L110: 50 and 100 rps, 100 ms) because no override command is recorded and no `peegeeq.campaign.*` property controls the operation mix. Result directories do not match the documented defaults (`calibration-b1-20260906` vs `benchmark-results/calibration`; `characterisation-framework-final-20260913` vs `characterisation-local`), and the two sibling directories `characterisation-framework-20260913` and `characterisation-framework-complete-20260913` are mentioned nowhere. The August validation table (L353–L357) has no run id, report or log; the root `20260817T091309430Z-…html` report is not cited. `scripts/benchmark/` exists and is empty; no document references it. Nine sections of the runbook are duplicated from the plan (L24–L30, L32–L37, L39–L52, L54–L59, L70–L91, L93–L99, L138–L141, L143–L150, L156–L224).

`PRODUCTION_BENCHMARK_PLAN.md` (1,483 lines) is ~77% dated implementation diary (§12–§37, L335–L1483). Its status line (L4) and §8 table say every phase is complete, but §21 L1023–L1024 says "B0, B1 and B2 are IN PROGRESS, while B3 through B6 are NOT STARTED. No phase is complete", rescued only by a banner at L337–L340 that a reader entering at §21 will not see; the §2 gap table (L46–L58) still describes the legacy runner as "Current implementation". L247 "This sequence supersedes the earlier B0–B6 plan" introduces a table that is itself B0–B6. L34 and L315–L316 direct the reader to the archived implementation plan and the archived U11 handover as "main plan" and "handover". §7's six scenario families were never reconciled with the executed scope (family 1 at two rates, L1435–L1437; L1359–L1361 lists what remains unsupported). Module-count arithmetic is inconsistent within the document ("seven benchmark/dependency modules" L1461 vs "all eight selected modules" L374, L420, L474, L552, L616, L729, L814, L990 for the same slice).

### 3.5 Other current documents

`README.md` has no module list and its "Current Features" table (L34–L48) does not mention the management server, REST API or React console — a reader cannot learn the reactor shape from it. Its benchmark section (L129–L137) documents only the legacy `-Pbenchmark-capture`. L54's "manually validated against PostgreSQL 15.17, 16.13, 17.11, and 18.3" carries no date (OPERATIONS L64 dates it 16 August on a 269-test reactor that predates REST and UI). README L40–L41 name `IF_ABSENT`, `IF_EXISTS`, `RESET` where the API and `NATIVE_SQL_API.md` use `ONLY_IF_ABSENT`, `ONLY_IF_PRESENT` and have no `RESET`.

`PEEGEEQ_CACHE_OPERATIONS.md` versions are correct (Java 21–26, Vert.x 5.0.8, Micrometer 1.17.0); its test counts (L64 "269-test", L73 "286 tests") are August figures stated as current.

`docs/prompt.md` is an "Imported sibling-repo architecture snapshot (December 22, 2025)" of the PeeGeeQ queue repository (`QueueFactory`, `MessageProducer`, outbox, bitemporal); it cites files that do not exist here (L83–L85), is referenced by no current document (only the archived code review's "keep prompt.md updated"), and is dated 15 March. It does not belong under `docs/`.

`docs/maven-settings-central.xml.example` is correct and consistent with `RELEASE_PACKAGING.md`.

---

## 4. Archive

All ten archived Markdown files carry "> **Archived 11 September 2026.**"; four name their successor (implementation plan → production benchmark plan; operation manifest → OpenAPI document and inventory tests; Playwright plan → executable test catalogue; mockups → production UI) and six are "retained as historical" with no successor named. Every link from a current document into the archive includes `archive/` and resolves. The problem is what those links claim:

| Current document | Cites archived document as |
|---|---|
| `MANAGEMENT_API.md` L26 | "The reviewed exact route inventory is [OPERATION_MANIFEST]" and "the accepted reactor topology … [BUILD_DECISION]" |
| `MANAGEMENT_API.md` L1630, `MANAGEMENT_UI_DESIGN.md` L26, L814 | "Its authoritative execution sequence is [UI_IMPLEMENTATION_PLAN]" |
| `MANAGEMENT_API.md` L1670, L1680; `COVERAGE_MATRIX.md` L42, L255 | The manifest as a live gate that changes "must update" |
| `MANAGEMENT_API.md` L24, L1587; `MANAGEMENT_UI_DESIGN.md` L24 | "Every control in the approved mockups is covered" |
| `COVERAGE_MATRIX.md` L74; `MANAGEMENT_OPERATIONS.md` L16 | The Playwright plan as the owner of gate evidence |
| `PRODUCTION_BENCHMARK_PLAN.md` L34, L315–L316 | The implementation plan as "main plan"; the U11 handover as "handover" |

Inside the archive, `PEEGEEQ_CACHE_UI_U11_HANDOVER_2026-09-04.md` L501 links `docs/design/PEEGEEQ_CACHE_MANAGEMENT_UI_IMPLEMENTATION_PLAN.md` without `archive/` (no longer resolves), and the archived Playwright plan says "Required minimum: 540" (L437) two lines before "539" (L439). The archived operation manifest (59 operations, 29 methods) is more current than the prose of two live documents.

---

## 5. Duplication map

Content that exists in more than one place and will drift:

| Content | Locations |
|---|---|
| Verification workflow rules (rebuild, `-DskipTests` exception, `Tee-Object`, scope, 539 pairing) | `CLAUDE.md` L11–L20; `TEST_COMMANDS.md` L6, L84–L88, L104–L106; `DEV_GUIDELINES.md` L1006–L1033 |
| No-mocking / Testcontainers mandate | `CLAUDE.md` L16; TDD L65; `DEV_GUIDELINES` L55–L66, L286–L293, L346–L352 |
| Vert.x 5 patterns guide | `DEV_GUIDELINES` L722–L987 and L1078–L1335 (full repeat) |
| Management route tables | `MANAGEMENT_API.md` §§7–14 and `MANAGEMENT_UI_DESIGN.md` §10.2–§10.7 (L568–L667, `{setupId}` vs `:setupId`) |
| Roles/session model, limits JSON, model list | API §4.1–4.3 / UI §5.3; API L464–L468 / UI L587–L593; API §16 / UI §11; API §15.1 / UI §12.2 |
| The 5 September gate sentence (557/557, PG 15.17–18.3, "11-module") | API L1634, UI L28, MATRIX L66, MANAGEMENT_OPERATIONS L16 |
| Capability-removal banner | API L3, UI L3, MATRIX L3 (identical text) |
| Schema versioning/upgrade contract | `NATIVE_SQL_API.md` L39–L47; `OPERATIONS.md` L37–L43 |
| `WriteBehindConfig`, lock/counter-not-buffered rule, listener connection, "where Redis wins" | `DESIGN.md` — see §3.3 |
| Benchmark runbook sections | `BENCHMARKS.md` ↔ `PRODUCTION_BENCHMARK_PLAN.md` — nine sections, see §3.4 |
| Jenkins job configuration | `JENKINS_CI_SETUP.md` L14–L24 and L156–L168 |

---

## 6. Things that are right

So that the fix list is proportionate: `CLAUDE.md` is accurate against the code (539, the count-pairing rule, the commands). `TEST_COMMANDS.md`'s npm script table (L137–L146), its "no `npm run test`" note and its 539 references match the module. `TDD_APPROACH.md`'s guard names, PostgreSQL 18.3-alpine default and loopback-server rule match. `NATIVE_SQL_API.md` matches the `V001` baseline. `PEEGEEQ_CACHE_OPERATIONS.md` versions, `MANAGEMENT_OPERATIONS.md` environment variables, port, `/health/ready`, `/metrics`, `VERIFY_FULL` and the runnable-jar name are correct. The coverage matrix's per-operation tables reconcile with the 59 OpenAPI operations. `README.md`'s quick-start API names (`defaults()`, `loadBootstrapSql()`, `SchemaBootstrapMode.APPLY`, Java 21–26, Vert.x 5.0.8) are correct. `JENKINS_CI_SETUP.md` is honest about its own currency ("Last reconciled: 2026-09-06") and correct that the Jenkins characterisation stage still runs the legacy capture. The archive banners are complete.

---

## 7. Verdict per file

| File | Lines | Verdict |
|---|---:|---|
| `CLAUDE.md` | 20 | Accurate; but mandates a contradictory document and duplicates `TEST_COMMANDS.md` |
| `README.md` | 143 | Mostly accurate; one broken link, REST/UI/module list missing, CI attribution incomplete |
| `docs/guidelines/PEEGEEQ_CACHE_DEV_GUIDELINES.md` | 4,402 | Contradictory; ~98% sibling-repository material; index cites six non-existent files |
| `docs/guidelines/PEEGEEQ_CACHE_TEST_COMMANDS.md` | 159 | Mostly accurate; module test-mass table wrong; garbled Node path |
| `docs/guidelines/PEEGEEQ_CACHE_TEST_COVERAGE_AND_TDD_APPROACH.md` | 360 | Stale inventory (17 August, no REST/UI); `-q` install steps; misplaced exception |
| `docs/PEEGEEQ_CACHE_OPERATIONS.md` | 73 | Mostly accurate; August test counts stated as current |
| `docs/PEEGEEQ_CACHE_LOGGING.md` | 76 | Stale; predates the REST server, which breaks its `slf4j-simple` scope rule |
| `docs/PEEGEEQ_CACHE_RELEASE_PACKAGING.md` | 56 | Stale; 8 of 10 modules; REST/UI publication undefined |
| `docs/PEEGEEQ_CACHE_MANAGEMENT_OPERATIONS.md` | 153 | Mostly accurate operationally; no gating banner; 557/11-module evidence; workstation JDK path |
| `docs/PEEGEEQ_CACHE_BENCHMARKS.md` | 359 | Mostly accurate; profile-name collision, unreproducible retained campaign, duplicated from plan |
| `docs/prompt.md` | 89 | Irrelevant sibling snapshot; misplaced |
| `docs/design/PEEGEEQ_CACHE_DESIGN.md` | 3,306 | Contradictory; March header, 7-module tree, §16/§19/§20/§28/§29 contradicted by code, broken numbering |
| `docs/design/PEEGEEQ_CACHE_NATIVE_SQL_API.md` | 47 | Accurate |
| `docs/design/PEEGEEQ_CACHE_MANAGEMENT_API.md` | 1,690 | Contradictory; banner-only update; denies lock operations; 10 operations unspecified; stale registration shape and counts |
| `docs/design/PEEGEEQ_CACHE_MANAGEMENT_UI_DESIGN.md` | 881 | Stale; no Advanced page, gating text, wrong component list, port mismatch, U0–U10 vs U11 |
| `docs/design/PEEGEEQ_CACHE_FUNCTIONALITY_COVERAGE_MATRIX.md` | 259 | Tables accurate; prose contradictory (60/59, 29/30/62, 3/6 checks, false documentation-gate claim) |
| `docs/design/PEEGEEQ_CACHE_JENKINS_CI_SETUP.md` | 194 | Stale on benchmarks (adapter "unfinished"); correct on the still-pending stage migration; build log ends red |
| `docs/design/PEEGEEQ_CACHE_PRODUCTION_BENCHMARK_PLAN.md` | 1,483 | Status line correct; body contradictory (§2, §21 assert superseded states); evidence unverifiable; ~77% diary |
| `docs/maven-settings-central.xml.example` | 12 | Accurate |

---

## 8. Recommended actions, in order

1. **Retire `PEEGEEQ_CACHE_DEV_GUIDELINES.md` as mandatory reading.** Move it to `docs/design/archive/` under a name that says what it is (an imported sibling-repository guide), and replace it with a short peegee-cache guideline (target under 300 lines) holding only what applies here: the Vert.x 5 `Future` composition rules, the no-mocking/Testcontainers mandate (stated once, cross-referenced from `CLAUDE.md`), the fail-fast configuration rules, and the multi-statement SQL caveat if it is verified to apply to this code base (README's quick start uses `pool.query(bootstrapSql).execute()`, so either the caveat is wrong here or the quick start is). Update `CLAUDE.md` L5.
2. **Make the coverage matrix the only place that states counts.** Delete the 60/557/30/"11-module" sentences from `MANAGEMENT_API.md` L1634, `MANAGEMENT_UI_DESIGN.md` L28, `MANAGEMENT_OPERATIONS.md` L16, and the matrix's own L13/L204/L235/L72, and replace each with a link to the matrix's §3. Fix the matrix's L13, L204, L235, L72, L143, L250. Add the gating banner to `MANAGEMENT_OPERATIONS.md`.
3. **Bring the API and UI designs into line with the code.** Delete `MANAGEMENT_API.md` L1115, L1587 (lock clause), L1134 (capabilities endpoint), L1687 (capability gate), L1645–L1658 (implementation order); add the ten M11 operations with method/path/schema; update the registration request and setup-details examples to include `runtime` and `limits`; resolve the pool-metrics example vs L1288. In `MANAGEMENT_UI_DESIGN.md` add the Advanced operations page to §6.1/§6.2/§17, delete L383/L873/L595–L597, fix L54's component list to `StatCard`, `SetupScopeBar`, `ConnectionStatus`, `ValueSelect`, fix L482's tag list, fix L6 vs L814, and decide the port (see 4).
4. **Fix the dev-mode port.** Either default `ManagementServerMain` to 8089 or point the Vite proxy at 8080; then make `MANAGEMENT_UI_DESIGN.md` L532/L538 and `MANAGEMENT_OPERATIONS.md` L27 say the same thing. This is a code change, not only a documentation one.
5. **Decide what `PEEGEEQ_CACHE_DESIGN.md` is.** Either mark it at the top as the original design record, superseded where it conflicts with `NATIVE_SQL_API.md`, `README.md` and `PEEGEEQ_CACHE_OPERATIONS.md`, and stop editing it; or repair it: update the header, the module tree (§13), delete §16 in favour of a link to `NATIVE_SQL_API.md`, replace §19 with the actual records, delete §20 in favour of `CacheTelemetry`, delete §28 and §29, fix the sub-section numbering, and move Appendix A and §12a.11 to the archive. The first option costs an hour; the second costs a day.
6. **Fix `README.md`**: the `NATIVE_SQL_API` link (L105); add a ten-module table; say that the PostgreSQL matrix runs in GitHub Actions on push/PR while Jenkins is a manual worker (and say the same in `PEEGEEQ_CACHE_OPERATIONS.md` L73 and `JENKINS_CI_SETUP.md`); replace the benchmark section with a pointer to `BENCHMARKS.md`; fix the `IF_ABSENT`/`IF_EXISTS`/`RESET` names at L40–L41.
7. **Refresh the test inventory once, then stop maintaining it by hand.** `TDD_APPROACH.md` §3 and `TEST_COMMANDS.md`'s module table should either be regenerated from the last saved reactor log (with its date and log name) or reduced to the Docker-required list. Add `peegee-cache-benchmarks` to `CLAUDE.md` L15's Docker-first list. Fix `TDD_APPROACH.md` L333–L334 and move L278–L290 under §"System property configuration". Fix `TEST_COMMANDS.md` L112–L113.
8. **Split the benchmark plan.** Keep §1–§11 and §37 as the plan (about 330 lines); move §12–§36 to `docs/design/archive/PEEGEEQ_CACHE_BENCHMARK_IMPLEMENTATION_LOG_2026-09.md`; delete the §2 gap table or mark every row delivered; rewrite L1023–L1024. Record the retained campaign's exact command and property overrides in `BENCHMARKS.md`, name the three `characterisation-framework-*-20260913` directories and say which is the accepted one, save at least the definitive gate log under `logs/`, and either use or delete `scripts/benchmark/`. Rename either the Maven profile or the Jenkins run mode so `benchmark-characterisation` means one thing, and open a tracked item for migrating the Jenkins stage to the JSON campaign.
9. **Update `LOGGING.md` and `RELEASE_PACKAGING.md`** for the REST and UI modules (what the runnable jar bundles; which of the ten modules are published to Central and which are excluded).
10. **Move `docs/prompt.md`** to the archive under a descriptive name, or delete it.
11. **Change the archive citations** listed in §4 from "authoritative"/"reviewed inventory"/"owner of evidence" to "historical record"; point the manifest references at the OpenAPI file and `ManagementOpenApiContractTest`. Fix the archived handover's L501 path.
12. **Add a "Last reconciled: <date> against <commit>" line** under the title of every current document, as `JENKINS_CI_SETUP.md` L4 already does, and remove the March/August "Date/Version" headers that contradict the file's content. When a document is edited for a banner, the reconciliation line must not be advanced unless the body was checked.

Items 1–4 remove every contradiction an engineer or agent would meet on the first day. Items 5–12 are hygiene and can be done one document at a time.

---

## 9. Cross-check note

The `.history/` directory (git-ignored) contains March 2026 migration files `V001` to `V006`. They show that the six-file layout described in `DESIGN.md` §28 was real at the time; it was later consolidated into the single `V001__create_peegee_cache_schema.sql` baseline that `NATIVE_SQL_API.md` documents.

---

## 10. Implementation record (24 September 2026)

All twelve actions in §8 were carried out on the same day. Every factual statement added to a document was checked against the code first; where a statement could not be checked it was left out or marked as unverified. An independent verification pass then read every changed document against the gathered code facts, and its findings were fixed before the files were written back.

| # | Action | Result |
|---|---|---|
| 1 | Retire the imported guidelines | The imported file moved to `docs/design/archive/PEEGEEQ_SIBLING_REPO_IMPORTED_GUIDELINES_2026-03.md` with a banner. A new 89-line `docs/guidelines/PEEGEEQ_CACHE_DEV_GUIDELINES.md` covers module layering (from the poms), the async model, resource ownership, configuration, SQL and schema, errors, logging and telemetry, tests, the management API and documentation rules. The multi-statement SQL caveat was checked: `PgTestSupport` and `PgSchemaMigrator` apply the whole `V001` through `query(sql).execute()` and every PostgreSQL test depends on objects defined late in that file, so the caveat applies to `preparedQuery` only and the README quick start is correct. `CLAUDE.md` now describes the file and adds `peegee-cache-observability` and `peegee-cache-benchmarks` to the Docker-first list. |
| 2 | Counts in one place | The coverage matrix §3 now has a count table, each value tied to the code that defines it, plus the latest recorded complete gate (`logs/capability-gating-removal-verify-20260910.log`) and a dated history. The API, UI design, management operations guide and the three banners link to it instead of restating numbers. |
| 3 | API and UI designs | API: new §9.9–9.13, §11.5 and §13.5 for the ten M11 operations (paths, profiles and fields from the OpenAPI schemas); registration and setup-details examples now carry `runtime` and `limits`; the runtime-monitoring example matches `AvailableLongValue` and `lifecycleState`; the lock and capability statements, the M0 implementation order and the manifest-as-gate text are gone; `namespace(String)` added to §16. UI design: Advanced page in the shell, routes and new §7.12; route tables list the M11 endpoints; component list, tag list, stack, state ownership, registration form, lock scope and phase status corrected. |
| 4 | Dev-mode port | Code change: `peegee-cache-management-ui/vite.config.ts` proxies `/api` and `/ws` to `127.0.0.1:8080`, the server's default. |
| 5 | `PEEGEEQ_CACHE_DESIGN.md` | Lighter option: a status banner with an authority table, notes at every section the code contradicts (§12a constraints, §15, §16, §19, §20, §27–§29), the seven-module tree replaced by the ten-module tree, sub-section numbering fixed under §15–§23 (46 headings), and a Part II heading added. The body was annotated, not reconciled. |
| 6 | README | Link fixed, module table added, CI described as GitHub Actions (automatic) plus Jenkins (manual), `SetMode` and counter operations corrected, benchmark section replaced by a pointer to the runbook. The operations guide and Jenkins setup describe CI the same way. |
| 7 | Test inventory and commands | TDD §3 now reads its numbers from the two dated logs instead of a hand-maintained table; the `-q` install step is replaced by the `CLAUDE.md` rebuild command; the system-property exception moved to the rule it qualifies; the historical Phase 3 plan is labelled. `TEST_COMMANDS.md` module table, profile list and Node path fixed. |
| 8 | Benchmark plan | The dated diary §12–§36 moved unchanged to `docs/design/archive/PEEGEEQ_CACHE_BENCHMARK_IMPLEMENTATION_LOG_2026-09.md`; the plan keeps §1–§11 plus "Current status and evidence" and the 15 September audit (1,483 → about 360 lines). The §2 gap table is labelled as the legacy harness; §11 lists open items (JENKINS-JSON-CAMPAIGN, SCENARIO-FAMILIES, EXTERNAL-CAMPAIGN, RETAINED-EVIDENCE). The runbook records the command that should reproduce the accepted campaign, which of the four `characterisation-framework-*-20260913` directories is accepted, and what is fixed in code rather than configurable. The four 13 September logs were copied from `target/` to `logs/` with a `-20260913` suffix. Code change: the Jenkins run mode `benchmark-characterisation` is renamed `legacy-benchmark-capture` (stage, output directory and log renamed with it). The empty `scripts/benchmark/` directory was removed. |
| 9 | Logging and release packaging | The logging standard covers the management server (its `slf4j-simple` runtime dependency and the missing Vert.x logger-delegate flag). Release packaging lists all ten modules and records, as an open decision, that nothing in the POMs keeps the four application modules out of the Central bundle. |
| 10 | `docs/prompt.md` | Moved to `docs/design/archive/PEEGEEQ_SIBLING_REPO_ARCHITECTURE_SNAPSHOT_2025-12-22.md` with a banner. |
| 11 | Archive citations | Current documents cite archived plans, manifest, build decision and mockups as historical records. The archived handover's plan path is annotated. |
| 12 | Reconciliation lines | Every current document has a `Last reconciled` line. The documents that describe the two code changes say so, because those changes are not yet committed; `DESIGN.md` says its body was annotated, not reconciled. |

Beyond §8, `peegee-cache-management-ui/docs/USER_JOURNEYS_GUIDE.md` still described per-setup capabilities in eight places; that text now describes role-based permission.

**Corrections to this review found while implementing it.** The coverage matrix's "3/3" and "six" infrastructure checks (§3.2) are not a contradiction: three checks existed before P7 screenshot acceptance on 5 September and six after it; the matrix now says so. The "L501" link in the archived U11 handover (§4) is not a broken link; the stale path is plain text at its line 12, now annotated. The root pom profile list originally given in V2 was corrected in §2.

**Verification of the code changes.** `vite.config.ts` is not exercised by any test. In a sandbox copy of the current UI module: `npm ci`, `npm run quality` clean, the complete Vitest suite with coverage (36 files, 168 tests, branches 85.56%) passing with one worker, and `npm run build` green; log `logs/ui-vite-proxy-8080-20260924.log`. With the default parallel workers on the 2-CPU sandbox, `setups-page.test.tsx` and `overview-page.test.tsx` timed out at 30 s; both pass alone with and without the change, so the timeouts are load-related, not caused by the change. The Jenkinsfile rename was checked by reading: no test, POM or other file refers to the old run-mode name. Neither change has been run through Maven or Jenkins.

**For the owner.**

- Commit the documentation and the two code changes, then run a Jenkins build to confirm the renamed run mode.
- The GitHub Actions job has a 30-minute timeout, and the latest complete local `verify` took 29:58. The matrix jobs are likely to time out once the browser catalogue runs in CI.
- Decide the Central publication scope before the first release (release packaging).
- The open benchmark items are in the plan's §11.
