# PeeGeeQ Cache Jenkins verification remediation — 25 September 2026

> **Document role:** HISTORICAL CI FAILURE AND REMEDIATION RECORD  
> **Job:** `PeeGeeQ-Cache`  
> **Jenkins:** `http://192.168.1.181:8080/job/PeeGeeQ-Cache/`  
> **Accepted build:** [#6](http://192.168.1.181:8080/job/PeeGeeQ-Cache/6/) at `de93bb7`  
> **Final result:** `SUCCESS`, with Jenkins reporting no test failures

## 1. Executive summary

Six Jenkins runs were reviewed. Builds #1 through #5 were diagnostic red runs; each either exposed a
real Linux/Chromium portability defect or revealed the next previously hidden failure after the
earlier module was repaired. Build #6 was the first complete green verification run.

The very large failure counts in builds #1 and #2 did **not** represent hundreds of unrelated
defects. One Linux session-address error prevented management setup registration, so every browser
scenario whose fixture depended on a connected setup failed at the same prerequisite. Once that
root cause was repaired, build #3 reduced the result to seven independent browser-level failures.
Those were corrected in two changes. Build #5 then passed the complete REST/browser module and
reached a platform-dependent benchmark assertion that earlier runs could not expose because Maven
had stopped at the REST failure. Correcting that final test produced the green build #6.

| Build | Revision | Result | Principal finding | Repair |
|---|---|---|---|---|
| [#1](http://192.168.1.181:8080/job/PeeGeeQ-Cache/1/) | `3ed1162` | Failed: 442 REST failures/errors | Linux setup registration failed, causing a broad browser-test cascade | `bb00cd2` |
| [#2](http://192.168.1.181:8080/job/PeeGeeQ-Cache/2/) | `18a35a1` | Failed: same 442 REST failures/errors | Documentation-only revision retained the same unfixed runtime defect | `bb00cd2` |
| [#3](http://192.168.1.181:8080/job/PeeGeeQ-Cache/3/) | `bb00cd2` | Failed: 7 browser tests | Contrast, screenshot fallback/timing, and PNG-comparison defects | `97a4432` |
| [#4](http://192.168.1.181:8080/job/PeeGeeQ-Cache/4/) | `97a4432` | Failed: 1 of 1,104 tests | Screenshot repaint assertion remained compositor-dependent | `f2ee48a` |
| [#5](http://192.168.1.181:8080/job/PeeGeeQ-Cache/5/) | `f2ee48a` | Failed: 1 of 1,259 tests | Benchmark test assumed Windows drive-path semantics on Linux | `de93bb7` |
| [#6](http://192.168.1.181:8080/job/PeeGeeQ-Cache/6/) | `de93bb7` | **Success** | All reactor modules passed; Jenkins reported no test failures | Accepted |

## 2. Why later failures appeared only after earlier fixes

The Jenkins `verify` selection runs the Maven reactor in module order. Maven stops downstream module
execution after a module fails. Builds #1–#4 therefore stopped in `peegee-cache-rest`; the benchmark
module was never able to provide meaningful acceptance evidence. Build #5 was the first run in this
sequence where all 546 REST browser and infrastructure tests passed, allowing
`peegee-cache-benchmarks` to run and expose its Linux path assertion.

This progression is expected fail-fast behavior:

1. repair the common setup prerequisite;
2. expose and repair the remaining browser assertions;
3. obtain a fully green REST module;
4. reach the benchmark module and repair its independent portability issue; and
5. rerun the complete reactor to prove that every module succeeds together.

## 3. Builds #1 and #2 — Linux management setup registration cascade

### Observed result

- Build #1 checked out `3ed1162`; build #2 checked out the documentation-only revision `18a35a1`.
- Both runs reported 440 REST assertion failures and two REST errors out of 545 REST
  browser/infrastructure tests.
- Across the complete published Jenkins result this appeared as 442 failed/error tests out of
  1,103 tests.
- Most failures ended at the shared prerequisite with `Locator expected to contain text:
  Connection succeeded`.
- Two packaging scenarios timed out after 30 seconds.
- Maven stopped at `peegee-cache-rest`; subsequent modules and pipeline modes were skipped because
  of the earlier failure.

### Root cause

Two Linux/CI networking assumptions combined in the management setup fixture:

1. `LocalTokenSessionManager` replaced the numeric immediate-peer address with the synthetic name
   `loopback`. Setup-mutation rate limiting later resolved the authenticated source address. Linux
   did not define that hostname, so the request failed and setup registration returned
   `INTERNAL_ERROR`.
2. `ManagementConsolePostgresFixture` did not authorize the exact Testcontainers PostgreSQL host
   address and port presented by the Jenkins Docker topology. The test policy assumed a narrower
   local-host classification than the containerized worker actually used.

The hundreds of scenario failures were therefore a common fixture cascade: workflows for setup,
overview, namespaces, entries, counters, locks, Pub/Sub, monitoring, shell navigation, shutdown,
accessibility, and other management features could not pass their initial database-connection step.
They were not hundreds of distinct application regressions.

Build #2 repeated build #1 because `18a35a1` changed documentation, not the affected implementation.

### Repair — `bb00cd2 Fix Linux management setup registration`

- Preserve the numeric immediate-peer address in local-token sessions instead of publishing the
  unresolvable synthetic hostname.
- Authorize the exact Testcontainers host address and port in the management-console fixture while
  keeping the allowance pinned to that fixture endpoint.
- Add coverage proving that the session identity exposes a resolvable loopback address.
- Validate the focused unit behavior and a representative Playwright setup scenario locally and in
  a Linux Maven container.

### Outcome in the next run

Build #3 passed the management setup suite: 56 tests, zero failures and zero errors. The broad
registration cascade was eliminated, leaving seven specific browser failures.

## 4. Build #3 — seven remaining browser regressions

### Observed result

Build #3 checked out `bb00cd2` and completed in approximately 25 minutes. Of 1,103 published tests,
1,096 passed and seven failed or errored:

| Failure group | Tests | Symptom |
|---|---:|---|
| Accessibility contrast | 3 | Two catalogue cases and one keyboard product journey reported `color-contrast` violations for gold `Rejected` tags |
| Packaging screenshot capture | 2 | Packaging scenarios timed out after 30 seconds |
| Screenshot pixel-change assertion | 1 | A visible value change produced byte-identical captured output |
| Privacy restoration assertion | 1 | Expected and actual PNG byte-array lengths differed by one byte |

### 4.1 Gold-tag accessibility contrast

The Ant Design gold tag used foreground `#d48806` on background `#fffbe6`. Axe measured a contrast
ratio of 2.75:1 for the 12-pixel normal-weight `Rejected` label; the applicable WCAG AA threshold was
4.5:1. This failed two parameterized accessibility cases and the populated keyboard-reachability
journey.

**Fix:** `97a4432` added a higher-contrast foreground override for the affected gold status tags.

### 4.2 Packaging scenarios without a visible focus target

The screenshot infrastructure expected to focus and capture a visible target. Raw resource or
packaging pages could legitimately have no visible focus candidate, leaving the capture workflow
waiting until Playwright timed out.

**Fix:** `97a4432` retained the required viewport/element evidence pair while adding a safe fallback
for pages whose requested focus target is invisible. A real Chromium regression test covers the
fallback.

### 4.3 Screenshot timing across Chromium presentation frames

The capture utility could take a screenshot before a visible DOM update had been presented by the
Linux Chromium compositor.

**Fix:** `97a4432` waits across two animation frames before capture so layout and paint work can
advance before pixels are read.

### 4.4 Privacy assertion compared encoded PNG bytes

The privacy restoration test required the sensitive panel to return to its exact masked visual
state. It compared compressed PNG byte streams, where encoder output can differ despite identical
decoded pixels. Jenkins produced arrays of 22,959 and 22,958 bytes, causing a false negative.

**Fix:** `97a4432` compares decoded pixel data instead of compressed PNG bytes. The assertion still
requires exact visual equality.

### Outcome in the next run

Build #4 reduced the result to one failure out of 1,104 tests. The accessibility, packaging, and
privacy cases all passed.

## 5. Build #4 — nondeterministic screenshot repaint assertion

### Observed result

Build #4 checked out `97a4432` and completed in approximately 23 minutes. The only failure was:

`ManagementBrowserScreenshotsIT.capturesActualBrowserPixelsWithoutMaskingOrChangingDomState`

The assertion expected a visible value update to change the captured pixels, but Jenkins Chromium
returned byte-identical images. The complete REST suite otherwise passed 545 of its 546 tests.

### Root cause

The test fixture changed only a small area of text. Even after the two-animation-frame capture wait,
that small update was not a deterministic presentation trigger on the Jenkins Linux Chromium
compositor. This was an infrastructure-test-fixture weakness rather than evidence that production
screenshots were masked.

### Repair — `f2ee48a Make screenshot repaint assertion deterministic`

- Add an explicit, high-area background-color change to the fixture in addition to the sensitive
  value update.
- Retain the assertion that screenshots reflect real visible DOM state.
- Retain the native-pixel comparisons proving that capture adds no masks or overlays.
- Run `ManagementBrowserScreenshotsIT` five consecutive times under Linux Chromium: four tests per
  run, 20 total passes.

### Outcome in the next run

Build #5 passed all 546 REST browser and infrastructure tests. This was the first run in the
sequence to reach and execute the complete benchmark test module.

## 6. Build #5 — Windows-specific benchmark path assertion

### Observed result

Build #5 checked out `f2ee48a` and completed in approximately 23 minutes. It published 1,259 tests:
1,258 passed and one failed. The failure was:

`BenchmarkLocalCampaignConfigTest.parsesFiniteLocalCampaignControls`

The assertion expected:

```text
C:/repo/benchmark-results/test-run
```

but Jenkins produced:

```text
/var/lib/jenkins/workspace/PeeGeeQ-Cache/peegee-cache-benchmarks/C:/repo/benchmark-results/test-run
```

The benchmark module ran 155 tests; 154 passed and this one assertion failed.

### Root cause

The test embedded `Path.of("C:/repo")` and treated it as universally absolute. That is an absolute
drive path on Windows, but on Linux the same string is a relative path containing a colon. Calling
`toAbsolutePath()` correctly resolved it beneath the Jenkins module working directory. Production
`resolveOutputDirectory` behavior was correct; the test expectation was platform-dependent.

### Repair — `de93bb7 Make campaign path test platform neutral`

- Construct an absolute repository-root fixture using the host platform's own `Path` semantics.
- Resolve `benchmark-results/test-run` against that root.
- Keep production code unchanged.
- Validate the targeted class: two tests, zero failures/errors/skips.
- Validate the complete benchmark module: 155 tests, zero failures/errors/skips, including its real
  PostgreSQL Testcontainers integration tests.

## 7. Build #6 — accepted green verification

Build #6 checked out `de93bb7` and completed successfully in approximately 27 minutes. Jenkins
reported `Tests (no failures)`, and the console ended with both Maven `BUILD SUCCESS` and Jenkins
`Finished: SUCCESS`.

The final reactor tail recorded:

| Module | Result |
|---|---|
| `peegee-cache-rest` | `SUCCESS` in approximately 23:25 |
| `peegee-cache-benchmarks` | `SUCCESS` in approximately 14.6 seconds |
| `peegee-cache-examples` | `SUCCESS` |

The complete Maven reactor finished in 26:17; Jenkins post-actions then published JUnit results,
archived artifacts and fingerprints, and deleted the allocated workspace. The optional PostgreSQL
compatibility, recorder calibration, and legacy benchmark capture stages were skipped by their
`RUN_MODE` conditions, as expected for the default `verify` selection; they were not failure skips.

## 8. Repair chain and verification evidence

| Commit | Purpose | Key verification |
|---|---|---|
| `bb00cd2` | Preserve resolvable local-token source addresses and permit the exact Testcontainers endpoint | Focused unit test and representative setup scenario locally and in Linux Maven |
| `97a4432` | Correct tag contrast, stabilize screenshot capture/fallback, and compare decoded privacy pixels | Clean REST reactor rebuild and focused Linux Chromium scenarios covering every build #3 failure |
| `f2ee48a` | Make the screenshot repaint fixture deterministic | Clean REST rebuild plus five consecutive Linux Chromium screenshot-class runs, 20/20 tests passing |
| `de93bb7` | Remove Windows-only path semantics from the benchmark test | Targeted class 2/2; benchmark module 155/155; full Jenkins build #6 green |

The final successful build demonstrates that the changes work together on the actual Jenkins Linux
worker. Focused local and container runs were used to shorten each edit/verify loop, but none was
treated as a substitute for the complete Jenkins acceptance run.

## 9. Lessons retained

- Treat a large family of tests failing at the same fixture prerequisite as a likely cascade; find
  the first shared boundary failure before changing individual scenarios.
- Do not publish synthetic hostnames across components that later perform DNS resolution. Preserve
  the numeric peer address when the numeric address is the security identity.
- Pin Testcontainers allowances to the fixture's exact resolved endpoint rather than assuming that
  every CI Docker topology presents PostgreSQL as loopback.
- Compare decoded pixels when the contract is visual equality; compressed PNG bytes are not a
  canonical image representation.
- Screenshot infrastructure tests need large, deterministic visual mutations when proving that a
  compositor has presented new pixels.
- Construct path fixtures with host-platform semantics unless the test is explicitly testing a
  foreign path syntax.
- A green upstream module can reveal a downstream defect that was always present but previously
  unreachable. Every repair therefore requires another complete reactor run before acceptance.

## 10. Final status

The remediation sequence is complete. `origin/master` contains all four repair commits, build #6 is
green, and the repository working tree was clean after the accepted run. Temporary Linux reproduction
containers and volumes used during diagnosis were removed after verification.
