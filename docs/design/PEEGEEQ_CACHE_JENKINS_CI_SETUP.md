# PeeGeeQ Cache Jenkins CI and benchmark worker

**Document role:** IMPLEMENTATION AND OPERATIONS GUIDE
**Last reconciled:** 2026-09-06

## Purpose and established pattern

`peegeeq-cache` uses the same trusted Linux/Jenkins/Docker boundary as the sibling `peegeeq`
repository. Jenkins runs natively and schedules this repository only on the node labelled
`peegeeq-linux`. The non-root `jenkins` account reaches the rootful Docker daemon through the
`docker` group and `/var/run/docker.sock`. Testcontainers provisions PostgreSQL directly; ordinary
tests do not start repository Compose environments.

The repository-owned [Jenkinsfile](../../Jenkinsfile) is the job definition. The separate Jenkins
job is named `PeeGeeQ-Cache`; it must point at this repository and must not reuse or modify the
sibling `PeeGeeQ` job.

### Live configuration state — 2026-09-06

The authenticated Jenkins server at `http://192.168.137.11:8080` now contains a separate Pipeline
job named `PeeGeeQ-Cache`. It is configured as **Pipeline script from SCM**, using Git repository
`https://github.com/mraysmit/peegeeq-cache.git`, branch specifier `*/master`, script path
`Jenkinsfile`, lightweight checkout, and job-level non-concurrent execution. It has no automatic
trigger. The existing `PeeGeeQ` job was not reconfigured.

The reviewed benchmark/Jenkins implementation is committed as `8996af9` and is present on
`origin/master`. The first attempted build checked out that exact revision, but Jenkins had not yet
persisted the declarative parameters and therefore supplied no `RUN_MODE`. That attempt is invalid
evidence: it exposed a first-build initialization defect before verification began. The follow-up
pipeline revision supplies explicit first-run defaults and propagates every environment-preflight
failure independently of console-log capture. Only a later completed build may satisfy the
acceptance checklist below.

Build #2 proved those corrections: it checked out the repaired revision, selected `verify` and
passed the complete worker/Docker contract. It then exposed a separate phase-selection error in the
preparatory build: Maven `clean install -DskipTests` still reaches the mandatory Playwright evidence
check in `verify`, where no report can exist because the tests were intentionally skipped. The
pipeline now uses `clean package -DskipTests` for compilation/packaging before the selected suite.
JUnit publication permits an empty result set only when an earlier stage has already failed; a
successful verification or compatibility execution still requires non-empty reports. Build #2 is
diagnostic RED evidence, not a test result.

Build #3 then exposed a Jenkins-controller parser compatibility issue before workspace allocation:
the controller's Groovy parser rejects a continued boolean expression when `&&` begins the next
line, although the newer local Groovy compiler accepts it. The operator now ends the preceding line.
This attempt is parser RED evidence only. Subsequent revisions must be checked with Jenkins' own
declarative-pipeline validator, not only the local Groovy compiler, before another build is started.

Build #4 validated the repaired pipeline path: controller parsing, SCM checkout, environment
preflight, preparatory packaging and entry into the full reactor all succeeded. The reactor then
found one real Linux UI failure out of 170 tests. A successful setup-detach request displayed its
success notice while the RTK-invalidated setup-list refresh was still asynchronous; under the
Jenkins worker's timing the row remained `CONNECTED` and never rendered `Connect` before timeout.
The lifecycle action now awaits an authoritative list reconciliation before it clears selection and
announces completion. A deliberately blocked loopback-list response reproduced the premature notice
as deterministic RED evidence, then passed after the repair. The full pinned-Node UI gate passes all
36 files and 170 tests with lint, type checking, generated API, coverage and production build.
Build #4 remains failed diagnostic evidence; a later full Jenkins build is required.

Build #5 checked out the setup-reconciliation repair, and the previously failing UI boundary passed
as part of all 170 frontend tests. The reactor then reached the Java browser suites, where every
launch failed before a scenario could execute because the worker has no branded Google Chrome at
`/opt/google/chrome/chrome`. This is worker-runtime RED evidence, not a product assertion failure.
The sibling `peegeeq` pipeline installs Playwright-managed Chromium rather than requiring a
machine-wide branded browser. This pipeline now follows that contract: after packaging has resolved
the pinned Java Playwright dependency, a dedicated stage invokes that exact version's bundled CLI
to install Chromium for the Jenkins account. Verification and compatibility runs explicitly select
the managed `chromium` distribution. Local execution retains `chrome` as its default, and the only
accepted launch values are `chrome` and `chromium`; focused tests prove the default, the CI choice,
and fail-closed rejection of any other value. Build #5 remains failed diagnostic evidence; the
repaired revision requires a fresh Jenkins run.

Docker-group membership is root-equivalent authority on the worker. Only trusted repository
revisions and trusted job administrators may execute or replay this pipeline.

## Pipeline selections

The `RUN_MODE` parameter has four fixed selections:

| Selection | Execution | Evidence and intent |
|---|---|---|
| `verify` | Complete Maven `verify` using the selected PostgreSQL image | Default repository gate, including Java, UI, Playwright and Testcontainers checks |
| `postgresql-compatibility` | Four sequential complete `verify` runs against PostgreSQL 15.17, 16.13, 17.11 and 18.3 | Compatibility evidence matching the GitHub Actions matrix; reports are copied per version before the next run |
| `recorder-calibration` | Repeated fresh-JVM `benchmark-calibration` invocations | JSON evidence for recorder contention, allocation, bounded-memory behaviour and checkpoint growth; not database capacity evidence |
| `benchmark-characterisation` | Parameterised legacy `benchmark-capture` invocation | Current PostgreSQL regression characterisation with deliberately non-binding sentinel gates; see the limitation below |

Every selection first performs a clean reactor rebuild with tests skipped. Test and benchmark
commands retain Maven's failure status through `bash -o pipefail` while saving complete logs.
Concurrent builds are disabled so separate Jenkins runs cannot compete for the worker or Docker.
For verification selections, the rebuild is followed by installation of the exact
Playwright-managed Chromium version declared by the REST module. The browser suite is then launched
with `-Dpeegeeq.playwright.browser=chromium`; this does not install or depend on branded Google
Chrome. Developer runs continue to default to the locally established Chrome channel.

The compatibility suite is sequential by design. Each version's Surefire and Failsafe XML is copied
under a version-specific path immediately after execution, so later Maven runs cannot overwrite its
evidence. A failure marks that version and the build failed but does not prevent the remaining
versions from being exercised.

## Benchmark parameters and evidence

Calibration accepts explicit fork count, heap, recorder concurrency, finite bucket count, warm-up,
measurement and observation-window durations, checkpoint cadence, and maximum evidence-file bytes.
Jenkins validates numeric values before invoking Maven. Forks run sequentially and each starts a
fresh JVM. JSON files are written beneath
`benchmark-results/$BUILD_TAG/calibration/` and archived even when a later fork fails.

Current database characterisation accepts repetition count, foreground concurrency, pool size,
warm-up, duration, PostgreSQL image and a truthful deployment-topology description. The pipeline
sets extremely permissive performance thresholds because this selection exists to observe a
deployment's limits, not to force measurements through a preselected throughput or latency target.
Operational or correctness failures still fail the build. Its self-contained HTML output is written
beneath `benchmark-results/$BUILD_TAG/legacy-characterisation/`.

The environment stage records the Jenkins build/node identity, topology description, UTC time,
kernel, exact Java/Maven/Git versions, account/group identity, Docker client/server/API versions,
Docker context/security mode, filesystem capacity, memory and swap. The existing legacy capture
also embeds host resources, JVM limits, Git revision/cleanliness, Docker information, PostgreSQL
image identity and the benchmark configuration in its report. Logs, results, Playwright artifacts,
JUnit XML and benchmark evidence are archived before Jenkins deletes only the allocated workspace.

### Current characterisation boundary

The production benchmark plan's new interval JSON schema, rate-controlled scheduler and checkpoint
writer exist, but its managed Vert.x execution adapter and campaign runner remain unfinished. The
`benchmark-characterisation` selection therefore runs the maintained **legacy HTML capture** and
stores it in a directory that says `legacy-characterisation`. It must not be described as the final
parameter-matrix degradation campaign or as comprehensive per-run JSON evidence. When that adapter
and campaign entry point are complete, this Jenkins stage must be migrated to them; do not silently
reinterpret legacy HTML as the planned JSON schema.

Recorder calibration already produces the new comprehensive JSON file per fork. Calibration is
instrumentation evidence only and does not measure PostgreSQL/cache deployment capacity.

## Worker contract and preflight

The current `peegeeq-linux` contract inherited from the sibling pipeline is:

- Temurin JDK 25 at `/usr/lib/jvm/temurin-25-jdk-amd64`, compiling this repository's Java 21 target;
- Maven under `/opt/maven` and a readable Maven toolchain entry for JDK 25;
- the `jenkins` account in the `docker` group;
- a local rootful socket at `/var/run/docker.sock`, owned by `root:docker`;
- `DOCKER_HOST` unset; and
- sufficient disk, memory and swap for the selected run.

The Jenkins account must also be able to write Playwright's normal per-user browser cache and
execute the managed Chromium binary. The pipeline obtains the browser through the pinned Java
Playwright CLI after the preparatory package phase, matching the sibling pipeline's managed-browser
practice and avoiding privileged operating-system browser installation.

The pipeline verifies each item before rebuilding. It records resources rather than treating the
current 4-vCPU/15-GiB worker as a universal baseline. That worker represents one characterised
deployment only. Comparative runs must keep ESXi reservation, VM snapshots, datastore, power
policy and competing workload controlled and must preserve the full environment record.

## Jenkins job configuration

Create a separate Pipeline job named `PeeGeeQ-Cache`:

1. Set **Definition** to **Pipeline script from SCM**.
2. Set **SCM** to **Git** and repository URL to
   `https://github.com/mraysmit/peegeeq-cache.git`.
3. Use the required SCM credential if anonymous checkout is unavailable.
4. Set the branch specifier to `*/master`.
5. Set the script path to `Jenkinsfile` and enable lightweight checkout when supported.
6. Do not schedule benchmark modes. They require an explicit manual parameter selection.
7. If SCM automation is enabled, let it use the default `verify` selection only.
8. Restrict configure, replay and build-with-parameters permissions to trusted administrators.

The Jenkins job cannot exercise a local, uncommitted Jenkinsfile. Commit and push the reviewed
pipeline before its first build. Do not run a build merely to test a job whose configured branch
does not contain `Jenkinsfile`.

## Docker maintenance gate

Do not upgrade or restart Docker, containerd, Jenkins, the worker VM or the ESXi host during a test
or benchmark. Before an approved maintenance window, allow the active build to finish and preserve
its artifacts. After the update, repeat the pipeline environment checks and run `verify` before any
benchmark. Record the before/after Docker engine, API and PostgreSQL image identities. A result
interrupted by infrastructure restart is invalid evidence, even if a wrapper process later exits
successfully.

## Acceptance checklist

- [x] `Jenkinsfile` is reviewed, committed and present on the configured remote branch.
- [x] `PeeGeeQ-Cache` is a distinct job; `PeeGeeQ` is unchanged.
- [x] The job is constrained to `peegeeq-linux` and concurrent builds are disabled.
- [x] Environment preflight passes as the Jenkins account.
- [ ] Default `verify` publishes non-empty JUnit results and archived diagnostics.
- [ ] Compatibility mode publishes evidence for all four PostgreSQL versions.
- [ ] Calibration archives one valid JSON file per requested fork.
- [ ] Characterisation archives its report and exact deployment record.
- [ ] No benchmark run overlaps host maintenance, backups, snapshots or another workload.
- [ ] The final JSON campaign stage remains explicitly pending until the production adapter exists.
