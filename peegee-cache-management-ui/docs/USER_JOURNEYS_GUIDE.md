# PeeGeeQ Cache — User Journeys Guide

**Audience:** console users and authorized operators  
**Scope:** desktop management console  
**Updated:** 5 September 2026

## Purpose

This guide describes complete user processes, from a starting need to a confirmed outcome. It is
organized by what a user wants to accomplish, not by individual controls or automated test IDs.
Each journey includes its prerequisites, a procedure, a worked explanation of the decisions and
visible results, completion checks, and recovery guidance. Read the explanation before carrying out
an unfamiliar procedure; the numbered steps then serve as a practical checklist.

The journeys are not a single script to execute against production. Use a disposable, authorized
setup for demonstrations, destructive operations, and deliberately induced failures. On production
systems, follow your organization's access and change-control procedures.

## Before you begin

- Obtain the console address and approved authentication method from the service operator.
- Use a supported desktop browser and layout. The reference screenshots use a 1440 × 900 viewport.
- Confirm your identity, role, active setup, and namespace before accessing data or making changes.
- Viewers perform permitted inspection; mutations and sensitive reveals require the appropriate
  permissions and setup capabilities. An unavailable control is not an invitation to bypass it.
- Have an approved PostgreSQL setup available for data journeys. Connection settings and trust
  profiles come from the service operator; do not guess credentials or broaden target access rules.
- For practice, use clearly identifiable disposable data, such as namespace `journey-demo` and key
  `sample-entry`. Never substitute an existing production key without checking its ownership.
- Treat a success message as the start of verification: inspect the returned outcome and, where
  appropriate, refresh or reopen the item to confirm its current state.

Passwords, bootstrap tokens, owner tokens, and revealed values must not be copied into tickets,
URLs, shared logs, or unapproved disclosures. Copying a revealed value to the clipboard is an explicit
disclosure outside the console; handle clipboard history and its destination accordingly.

### Terms used throughout this guide

**Setup** means a registered database/runtime connection known to the management server. It is not
the same thing as a namespace. **Namespace** groups related cache items within the selected setup.
A key is meaningful only together with its namespace and setup; finding `sample-entry` in one scope
does not identify a similarly named entry elsewhere.

**Version** is the server's identifier for an item's current revision. A version-checked change says
"apply this only if the item is still the revision I inspected." It does not mean "replace whichever
revision happens to exist when the request arrives." **TTL** is the remaining lifetime before expiry.
Where a field says milliseconds, `60000` means one minute; entering `60` would mean only 60 milliseconds.
**Persistent** means no expiry is assigned, not that the value can never be changed or deleted.

**Capability** means an operation the selected setup and server advertise as available. **Permission**
means the authenticated user is allowed to perform it. A journey may require both. A missing mutation
button can therefore be a correct outcome for a viewer or for a setup that does not offer that feature.

**Confirmed result** means the server returned an outcome and the user checked the relevant current
state. A timeout leaves the outcome uncertain: a request may have reached the server even if its
response did not reach the browser. Read the state before repeating a non-idempotent action such as
a counter adjustment or message publication.

### Using the worked examples

Examples below use disposable data in `journey-demo`. They describe expected transitions, not fixed
screen snapshots: timestamps, generated identifiers, versions, and live totals will differ. Do not
copy a sample version or confirmation phrase; always use the current value shown by your console.
Record non-sensitive observations such as the target, operation, time, result, and any unresolved
issue. Do not record a revealed value merely to prove that you saw it.

### Reading the screenshots

Each journey includes relevant screenshots beside its procedure and explanation. Captions identify
the visible controls, decisions, or outcomes to look for, including where a capture shows only a
recovered or cleared state rather than an intermediate action.

The images come from the existing passing browser evidence and use test data such as setup
`browser-postgres`, namespace `logical-orders`, and key `customer:1`. These are not replacements for
the `journey-demo` examples or addresses and credentials to reuse. Images from different checks can
show different keys, versions, counts, and timestamps; they are illustrative states, not a single
continuous execution of the journey.

Screenshots reproduce the actual development UI without capture-time masking, black rectangles,
redaction, or replacement values. Visible values and editors are captured as rendered. Native
password controls and application states such as **Value hidden** remain exactly as the browser
displays them; screenshot capture does not change the application's reveal/hide behavior.

Images use the project's flat, descriptively named screenshot directory. Open an image at full size
when you need to read small controls. For a fresh checkout, generate or republish the evidence using
the [screenshot instructions](screenshots/README.md); the PNGs are generated artifacts.

## Journey directory

1. [Sign in and complete a secure session](#1-sign-in-and-complete-a-secure-session)
2. [Access the console through a trusted proxy](#2-access-the-console-through-a-trusted-proxy)
3. [Connect and manage a database setup](#3-connect-and-manage-a-database-setup)
4. [Select and restore a working context](#4-select-and-restore-a-working-context)
5. [Investigate database health and usage](#5-investigate-database-health-and-usage)
6. [Create and maintain a cache entry](#6-create-and-maintain-a-cache-entry)
7. [Resolve a conflicting entry update](#7-resolve-a-conflicting-entry-update)
8. [Safely delete entries in bulk](#8-safely-delete-entries-in-bulk)
9. [Manage a counter throughout its lifecycle](#9-manage-a-counter-throughout-its-lifecycle)
10. [Investigate and release a lock](#10-investigate-and-release-a-lock)
11. [Complete an owner-controlled lock lifecycle](#11-complete-an-owner-controlled-lock-lifecycle)
12. [Publish and receive a message](#12-publish-and-receive-a-message)
13. [Recover interrupted live monitoring](#13-recover-interrupted-live-monitoring)
14. [Perform batch cache maintenance](#14-perform-batch-cache-maintenance)
15. [Inspect sensitive data and leave safely](#15-inspect-sensitive-data-and-leave-safely)
16. [End an active operational session cleanly](#16-end-an-active-operational-session-cleanly)

## 1. Sign in and complete a secure session

**Goal:** enter the local console, perform authorized work, and leave without retaining access.

**Before starting:** the server uses local-token authentication and the service operator has supplied
the current single-use bootstrap token through an approved secure channel.

1. Open the exact local console address supplied by the operator.
2. Enter the bootstrap token in the password-style login control and submit it.
3. Confirm that the authenticated shell displays the expected identity and permissions.
4. Select an available setup and perform the intended inspection or maintenance.
5. Choose **End local session** when finished.
6. Confirm the login screen returns and protected content is no longer accessible.

### Screenshots along the journey

![Local console connection gate](screenshots/authentication-unauthenticated-local-access-shows-only-the-connection-gate-viewport.png)

*Steps 1–2 — The unauthenticated page exposes only the connection gate. Enter the operator-supplied token in the native password-style control shown here.*

![Authenticated console showing local-operator and Operator role](screenshots/authentication-authenticated-shell-shows-canonical-local-actor-viewport.png)

*Steps 3–4 — The header identifies the local operator and role. No setup is selected yet: authentication alone does not establish the database scope.*

![Connection gate after successful local logout](screenshots/authentication-successful-logout-returns-to-connection-gate-viewport.png)

*Steps 5–6 — After successful logout, the protected navigation is replaced by the connection gate. Also check reload as described below.*

### Worked explanation: entering, working, and leaving

Suppose you need to inspect a development cache for a short maintenance task. The service operator
provides the console address and a bootstrap token. That token is an admission credential used once
to establish a session; it is not a password intended for repeated logins. Open the supplied address
directly and enter the token only into the login control. Changing the host spelling or origin is
not a troubleshooting shortcut because the service applies exact-origin security checks.

After submission, wait for the authenticated shell. Seeing the application's logo alone is not enough:
verify that the user identity, role, and protected navigation are available. Do not continue if a login
error remains or if the displayed identity is unexpected. Once authenticated, normal requests use the
session established by the server; you should not need to enter the bootstrap token into another page.

Perform the actual work only after checking the active setup. For this example, open Overview and
confirm you can inspect the intended development setup. A successful login does not prove that the
correct database is selected. The authentication and data-scope checks address different mistakes.

At the end, choose **End local session** and wait for the login state. If the server rejects or cannot
complete logout, the authenticated shell can remain visible so you can retry. Treat that as an open
session, not a cosmetic error. After successful logout, reload once and verify that protected content
does not return. Reusing the consumed token is an acceptance-test branch, not a normal way to start
the next session; obtain a valid new access path from the operator.

**Completion record:** "Authenticated as the expected user, inspected the approved setup, ended the
local session, and confirmed reload remained unauthenticated." No token belongs in that record.

**Complete when:** work is finished, the local session has ended, and reloading does not restore
protected access. The consumed bootstrap token must not provide another session.

**If something goes wrong:** invalid, consumed, or expired credentials require operator assistance;
do not repeatedly replay a token. If logout reports a failure, do not assume it succeeded: retry and
verify that the protected shell disappears. A session that expires while working also requires a
valid authentication path; reload does not renew an expired local session.

## 2. Access the console through a trusted proxy

**Goal:** use centrally managed identity and permissions without a local bootstrap token.

**Before starting:** the deployment is configured for trusted-proxy authentication and you have the
approved HTTPS address and identity-provider access.

1. Open the approved proxy address and complete any identity-provider sign-in required.
2. Confirm that the console displays the expected user and role without a local-token login form.
3. Select an available setup and perform work permitted by that role.
4. If identity, role, or session validity changes, allow the console to revalidate through the proxy.
5. Confirm that access after revalidation reflects the current identity and permissions.
6. When finished, follow the proxy or identity provider's sign-out procedure.

### Screenshots along the journey

![Settings showing trusted-proxy authentication and viewer identity](screenshots/authentication-packaged-console-bootstraps-from-trusted-identity-headers-without-local-token-ui-viewport.png)

*Steps 2–5 — Settings identifies the user, viewer role, TRUSTED_PROXY authentication, and connected REST session. This local test address illustrates the authentication state; use your operator's approved proxy address, not the address in this capture.*

### Worked explanation: centrally controlled identity

In this scenario, your organization supplies an HTTPS console address behind its identity service.
You may already be signed in to that service, or it may ask you to authenticate. Either way, the
console should obtain your identity through the trusted deployment path. It does not ask you to
invent or obtain a local bootstrap token. A local-token login page at an address expected to use
the proxy is a reason to check the deployment with the operator.

Imagine that your account has viewer access. Once the console opens, verify the displayed identity
and use a permitted read-only journey, such as inspecting namespace usage. Mutation controls should
not become available merely because the underlying database is writable. If an operator changes your
role, the server revalidates identity on protected requests and can rotate the session. Check the
newly displayed permissions before continuing; do not rely on what was allowed earlier in the day.

A bounded session can require revalidation while the page is open. When that happens, allow the
approved proxy/identity flow to complete and verify the resulting user and scope again. An unresolved
authentication error means that displayed data may be old and new actions may be denied. Repeatedly
refreshing does not fix incorrect proxy configuration or an account that has lost permission.

When leaving, use the organization's sign-out procedure. This is deliberately different from journey 1:
the console does not own the identity-provider session and does not provide local-token logout for it.
On a shared workstation, closing only the console tab may leave the central identity session active.

**Completion record:** identify the expected role and completed task, and state which approved sign-out
or revalidation action was used. Never include proxy headers, cookies, or identity tokens.

**Complete when:** the intended work is performed under the correct identity and session handling
remains controlled by the trusted authentication service.

**If something goes wrong:** stop if the identity or role is unexpected. Contact the service operator
for proxy or origin errors. Do not access the backend directly or supply identity headers yourself.
Closing a tab is not equivalent to signing out of the identity provider; local-token logout does not
apply to this mode.

## 3. Connect and manage a database setup

**Goal:** make an approved database available in the console, manage its connection lifecycle, and
remove its registration when it is no longer needed.

**Before starting:** setup-management permission, approved connection settings, runtime settings,
and a server-owned trust profile are available.

1. Open **Setups** and start setup registration.
2. Enter the setup identity, database connection settings, trust profile, and required runtime settings.
3. Test the connection. Resolve validation or connectivity failures before continuing.
4. Register the setup and inspect its details and health information.
5. Choose **Use setup** and confirm it becomes the active setup.
6. When appropriate, detach it and confirm it is no longer the active connected scope.
7. Reconnect it, verify health, and select it again to resume work.
8. When the registration is no longer needed, choose **Forget**, review the confirmation, and confirm
   that the setup disappears from the registered list.

### Screenshots along the journey

![Register setup dialog with database and TLS settings](screenshots/setup-primary-register-action-opens-tls-dialog-viewport.png)

*Steps 1–2 — Review the setup identity, complete database destination, and approved trust profile. The form is shown as rendered, including its native password control; the visible TLS mode is VERIFY_FULL.*

![Runtime settings and successful connection test](screenshots/setup-successful-connection-test-preserves-registration-fields-viewport.png)

*Step 3 — The lower part of the registration dialog shows runtime options and the connection/schema result. A successful test does not submit the registration; review the settings before registering.*

![Registered and selected setup with lifecycle actions](screenshots/setup-registered-setup-row-shows-connected-runtime-viewport.png)

*Steps 4–8 — The setup row is Connected and Selected, with Details, Test, Detach, and Forget actions. Health is still Not checked in this capture; do not mistake connection state for a completed health assessment.*

### Worked explanation: connection settings and lifecycle decisions

For a disposable demonstration, register an approved setup called `journey-demo-setup`. The setup ID
is the stable identifier used by the console, whereas the display name is its readable label. Check
the target database and schema independently: a reachable host can still contain the wrong database
or an unintended schema. The password should remain in the dedicated password control, and the trust
profile must be one provisioned by the service operator.

Review the registration form in these groups:

| Settings | What the user must establish before registering |
|---|---|
| Setup ID and display name | The registration can be identified unambiguously and is not a duplicate of another intended target. |
| Host, port, database, schema | The complete destination is authorized; a familiar host name alone is insufficient. |
| Username, password, trust profile | The credentials and server-owned trust settings are approved for this target. TLS mode is not a setting to weaken for convenience. |
| Pool size | Use the approved allocation rather than increasing it simply because a test is slow. |
| Default TTL and expiry sweeper | Understand the default lifetime and whether background expiry cleanup is enabled. Expiry semantics and cleanup scheduling are different concerns. |
| Write-behind, Pub/Sub, schema bootstrap | Confirm the intended runtime behavior and ownership of schema provisioning before selecting these options. |

**Test connection** establishes whether the supplied configuration can reach and assess the target.
Read both the connectivity result and the reported schema state. Testing is not the same as registering:
after a successful test, the setup still needs to be submitted and appear in the registered list.
Likewise, a successful test is an observation at that moment, not a guarantee against a later outage.

Open **Details** after registration and check the runtime settings as well as database readiness. If
schema is provisioned externally, the responsible operator must have prepared it; choosing **Apply
bundled schema at startup** is an explicit provisioning decision, not a generic repair button.

The lifecycle controls have distinct purposes. **Use setup** selects the working target. **Detach**
ends its active connection lifecycle and can clear the active scope; it does not mean delete cached
data. **Connect** makes a retained registration available again. **Forget** removes the registration
from management. Before detaching or forgetting, finish dependent work and stop relevant subscriptions.
Verify each resulting state before taking the next action rather than clicking through confirmations.

**Completion record:** retain the setup identifier, intended target description, test/health outcome,
and final registration state. Exclude the password and other secret configuration values.

**Complete when:** the setup has passed through the intended states, and a forgotten setup is no
longer selectable. Forgetting a console registration is not a database-deletion procedure.

**If something goes wrong:** a forbidden target, failed trust check, or unreachable database must be
resolved through the operator's approved configuration. Do not relax security settings to make the
test pass. Cancel registration if the target or credentials cannot be verified.

## 4. Select and restore a working context

**Goal:** work against the intended setup and namespace consistently across navigation and reloads.

**Before starting:** at least one connected setup and an accessible namespace are available.

1. Select the intended setup and verify its name in the active scope indicator.
2. Open **Namespaces** and choose the namespace required for the task.
3. Navigate to **Keys**, **Counters**, or another available feature.
4. Check that the displayed content belongs to the intended scope.
5. Reload the page and confirm that the restored scope is still valid.
6. If switching scope, verify the new setup and namespace before performing any mutation.

### Screenshots along the journey

![Key Browser scoped to logical-orders in the selected setup](screenshots/shell-key-browser-uses-namespace-selected-by-route-viewport.png)

*Steps 1–4 — Check both the setup selector and the namespace above the key list. The metadata rows belong to this scope; a matching key name in another setup is a different target.*

### Worked explanation: preventing work against the wrong target

Suppose two approved setups both contain a namespace named `orders`. A familiar namespace name does
not identify which database you are using. Start by verifying the setup scope indicator, then select
the namespace, and finally inspect the identity of the item on its detail page. These three checks
reduce the risk of editing a correct key in the wrong environment.

Navigate from Namespaces to Keys and open a harmless demonstration entry. Confirm that the visible
namespace and key agree with the selected scope. Returning to another feature should preserve valid
context where supported, but do not infer that every operation uses the same namespace automatically.
In particular, Advanced batch inputs include explicit namespace/key pairs and require their own review.

Reload is useful as a restoration check. The application revalidates a stored selection rather than
treating browser storage as authority. If the setup is still connected and permitted, expect a usable
restored context. If it has been detached or forgotten, a cleared selection or a request to choose
another setup is safer and more correct than showing protected data under an obsolete scope.

When moving between setups, finish or cancel open editors first and clear revealed values. Then
select the new setup and let its capabilities load. A feature available in one setup may disappear in
another. Do not interpret that difference as lost data until you have checked the selected setup and
its advertised capabilities.

**Completion record:** identify the setup and namespace used, the navigation/reload check performed,
and whether the final context remained valid. If context was cleared, record why and what was selected
next rather than describing the clearing itself as a failure.

**Complete when:** navigation and reload retain only valid scope, with data and available controls
matching the selected setup's capabilities.

**If something goes wrong:** if the setup was detached, forgotten, or is no longer accessible, select
a valid connected setup again. Do not act on stale displayed data while scope or capabilities are
being revalidated.

## 5. Investigate database health and usage

**Goal:** turn an operational question into a verified view of usage, health, and relevant activity.

**Before starting:** inspection permission and a selected setup are available.

1. Open **Overview** and inspect the available totals and health indicators.
2. Open **Namespaces**, apply a relevant filter, and page through matching results.
3. Open a namespace to inspect its detailed statistics and data categories.
4. Export the supported namespace results when a local working record is needed.
5. Review **Monitoring** and the relevant activity information for additional context.
6. Refresh and confirm that the information applies to the intended setup and observation period.

### Screenshots along the journey

![Overview showing recent activity and namespace usage](screenshots/overview-overview-loads-all-monitoring-panels-viewport.png)

*Steps 2–4 — This scrolled portion of Overview shows recorded activity and a namespace summary with its observation time. Use the summary to choose where to investigate; it is not a screenshot of every health panel.*

![Namespace details showing resource totals and inspection tabs](screenshots/namespace-namespace-details-shows-exact-totals-and-resource-tabs-viewport.png)

*Steps 4–6 — Namespace details separates live entries, counters, locks, expiry, and estimated storage, with tabs for investigating the underlying resources. Read the observation timestamp alongside the totals.*

### Worked explanation: turning an observation into a useful finding

Consider a report that cache usage has increased. Begin with the relevant setup on Overview and note
the observation time and the available totals. A single total rarely explains the change: use the
namespace list to identify where the data is concentrated, then open the relevant namespace for a
more focused view. Do not compare figures from different setups as though they describe one system.

Apply a namespace prefix or status filter that matches the question, then use the page controls to
review results. A filter changes the population you are inspecting; reaching the end of the visible
page is not necessarily reaching the end of that population. Use the offered next/previous controls
instead of trying to edit or interpret the underlying cursor. Concurrent activity can also change
results between observations, so record the time rather than expecting every independently refreshed
screen to form one frozen snapshot.

**Export namespaces** produces a JSON download for the applied namespace query, not a screenshot of
the visible page. Inspect its item count and any truncation indication. The console reports **server
limit reached** when an export is bounded; do not describe such a file as a complete inventory. Narrow
the investigation or agree a suitable operational extraction method if a full inventory is required.

Use Monitoring and activity information to add context to the observed usage. For example, a count
change accompanied by recent mutations is different from a measurement that stopped updating after
a connection loss. Treat unavailable values, stale measurements, and actual numeric zero as separate
states. This guide does not prescribe production thresholds: compare observations with the limits and
baselines agreed for your deployment.

**Completion record:** state the question, scope, filters, observation time, relevant results, export
limitations, and the next action or conclusion. That makes the journey useful to another operator
without including cache values or credentials.

**Complete when:** you can identify the affected scope and describe the observed condition using
the displayed results and, where used, the exported data.

**If something goes wrong:** distinguish unavailable measurements from zero values. A disconnected
or stale live view is not current database health. Follow journey 13 before drawing conclusions from
interrupted monitoring.

## 6. Create and maintain a cache entry

**Goal:** create a value, maintain its content and lifetime, and remove it when no longer required.

**Before starting:** entry-mutation permission, the correct setup and namespace, and an unused or
explicitly approved key are available. Reveal permission is required for the value-inspection steps.

1. Open **Keys**, start entry creation, and enter the namespace, key, value type, and value.
2. Select the intended creation/set behavior and lifetime settings, then submit.
3. Locate the entry and inspect its type, version, and expiry metadata.
4. Explicitly reveal the value if needed, choose the appropriate display format, and verify it.
5. Hide the value when inspection is complete.
6. Edit the entry using its current version and verify the committed result.
7. Set a future expiry and verify the displayed lifetime. If persistence is required, remove that
   expiry before the entry expires and verify that it is persistent.
8. Use the touch operation when required, explicitly choosing any offered TTL refresh value rather
   than assuming that touching always extends the lifetime.
9. Delete the entry using the displayed confirmation and current version, then refresh and confirm absence.

### Screenshots along the journey

![Create entry dialog with type and lifetime controls](screenshots/entry-open-the-create-entry-dialog-element.png)

*Creation steps — Enter a new key, select the actual stored value type, and decide between a positive TTL and persistence. The empty value editor is ready for input; the dialog states that the key must still be absent at commit.*

![Entry details after a committed TTL update](screenshots/entry-set-a-positive-entry-ttl-viewport.png)

*TTL verification — The success notice is accompanied by version 4, an expiry time, approximately 60 seconds remaining, and EXPIRING status. Verify this metadata rather than relying only on the submitted 60000-millisecond input.*

### Worked explanation: a controlled entry lifecycle

Use a disposable string entry such as namespace `journey-demo`, key `sample-entry`, and a harmless
value such as `first revision`. The first decision is whether the operation should create only a
missing key, replace an existing entry, or apply another supported set mode. Choose the behavior
that matches the task. An unconditional set can overwrite data and is not a general substitute for
understanding why a creation precondition failed.

After saving, locate the entry through Keys and open its details. Verify namespace, key, type, version,
and expiry before revealing the value. The list and metadata view deliberately do not expose the
stored value by default. **Reveal value** is a separate authorized action; the reason field is optional
but should contain a concise operational reason when used. Check the value in an appropriate format,
then choose **Hide value**. The application returns to its Value hidden state after hiding.

Next, edit the value to `second revision` using the current version. Read the returned success or
conflict result before proceeding. Reopen or refresh metadata after a successful change and verify
the current state rather than assuming the version increments by a particular amount. Other activity
may have occurred between observations.

For the lifetime exercise, enter `600000` in **TTL milliseconds** and select **Set TTL**. This gives a
ten-minute demonstration window, avoiding an expiry while you are still reading the guide. Verify
that an expiry is now shown. Then select **Make persistent** before that window ends and confirm the
entry no longer has a scheduled expiry. Expiring an entry and deleting its physical storage are not
the same event; do not rely on background cleanup timing as the definition of whether a value is live.

The touch controls require another deliberate choice. Leaving **Refresh TTL milliseconds (optional)**
blank does not request a new TTL. Supplying a positive value requests the supported lifetime refresh
alongside the touch. Choose **Touch entry** and inspect the resulting metadata; the word "touch" alone
must not be interpreted as "make persistent" or "extend by the old TTL."

Finally choose **Delete entry**, read the target in the dialog, and enter the exact value requested by
**Confirm entry key**. **Confirm delete** remains unavailable until the confirmation matches. After
success, refresh the relevant list using the same filters and verify that the entry is absent. In
production, the journey may intentionally end at an updated or persistent entry instead: deletion is
the cleanup step for this disposable demonstration, not a requirement to remove useful application data.

**Completion record:** capture the key's identity and the requested/observed state transitions, never
the revealed contents. A screenshot of metadata is sufficient for the operational completion record.

**Complete when:** each requested change has a confirmed result and the final entry state matches
the task—deleted at the end of this demonstration lifecycle.

**If something goes wrong:** resolve validation errors without changing unrelated fields. For a
version conflict, follow journey 7. If the entry has already expired or disappeared, refresh before
deciding whether recreation is appropriate.

## 7. Resolve a conflicting entry update

**Goal:** complete an intended edit without silently overwriting another user's change.

**Before starting:** an entry edit has encountered a version mismatch. Deliberately producing a
concurrent change is a controlled test activity, not a normal production step.

1. Read the conflict response and retain the intended edit for comparison.
2. Reload or reopen the entry's current metadata and value, using reveal permission where necessary.
3. Compare the current content with your intended change.
4. Decide whether to reconcile and retry, abandon the edit, or consult the other owner.
5. If retrying, apply the reconciled edit against the newly observed version.
6. Verify the successful result and the resulting current version.

### Screenshots along the journey

![Version mismatch with the entry editor still open](screenshots/entry-retain-form-state-after-a-concurrent-version-conflict-viewport.png)

*Conflict branch — VERSION_MISMATCH appears while the editor remains open and the current metadata shows version 4. The editor retains the proposed value. Reconcile that change with current state before submitting another version-checked request.*

### Worked explanation: reconciling intent rather than forcing a save

Imagine that you open an entry at version 7 to correct one field. Before you submit, another operator
saves a different change, producing a newer version. Your submission carries the revision you saw.
A version-mismatch response means the server refused to apply that stale decision; it does not prove
that your intended change was wrong or that the other operator's change should be discarded.

First preserve your intended edit in the still-open workflow or another approved temporary location
if needed. Do not copy a sensitive value into a ticket. Read the latest metadata and, if justified,
reveal the current value. Compare the meaning of the changes, not merely their version numbers. If
you intended to correct a label and someone else changed a delivery state, preserving the delivery
state may be essential when preparing the reconciled value.

Choose explicitly between three outcomes. **Abandon** the edit when the current value already meets
the need or your original assumption is no longer valid. **Reconcile and retry** when you understand
both changes and have authority to combine them. **Escalate** when ownership or business meaning is
unclear. The console supplies concurrency protection; it cannot decide which business intent wins.

For a retry, reopen or refresh the editing workflow so it uses the current revision, review the full
value that will be submitted, and save once. A successful reconciliation still needs verification of
the resulting metadata and, where necessary, value. If a further conflict appears, there is another
concurrent change to understand. Repeatedly forcing a save or choosing unconditional overwrite would
remove the safeguard rather than resolve the situation.

**Completion record:** record that a conflict occurred, whether the change was abandoned, reconciled,
or escalated, and the verified final outcome. The example versions above are illustrative; never type
them into a live request merely because they appear in this guide.

**Complete when:** the edit is either deliberately abandoned or committed against current state,
with no accidental overwrite of an unseen change.

**If something goes wrong:** repeated conflicts indicate ongoing concurrent activity. Coordinate
with the other operator rather than repeatedly retrying or switching to unconditional overwrite.

## 8. Safely delete entries in bulk

**Goal:** delete an explicitly reviewed set of entries and account for every reported outcome.

**Before starting:** bulk-deletion permission and an approved deletion scope are available.

1. Confirm the active setup and namespace, then filter or select the intended entries.
2. Request a deletion preview and review its target scope and count.
3. Cancel if the preview includes anything outside the approved scope.
4. Enter the exact confirmation phrase displayed by the console.
5. Execute the previewed deletion once.
6. Read the result, distinguishing deleted entries from conflicts or other non-deleted targets.
7. Refresh the list and inspect any remaining entries before deciding on further action.

### Screenshots along the journey

![Bulk deletion preview with target scope and confirmation phrase](screenshots/entry-open-an-explicit-target-preview-element.png)

*Preview review — Confirm setup, namespace, resolved count, sample key, and preview expiry before typing the phrase displayed by your own console. The delete button is disabled while the confirmation is incomplete.*

![Bulk deletion result with one deletion and one conflict](screenshots/entry-report-a-concurrent-partial-conflict-viewport.png)

*Outcome review — This separate conflict example reports 1 of 2 previewed entries deleted and 1 conflict. The remaining list must be reassessed; the green notice does not mean every target was deleted.*

### Worked explanation: preview is a reviewed plan, not a permanent authorization

Suppose you are approved to remove three disposable entries with a known prefix. Begin by checking
both the active scope and the applied filters. If selecting rows, review the selected set rather than
assuming that all filtered results or all pages are selected. If using a filter-based preview, remember
that its target set can be broader than the currently visible rows. The server's preview is the point
at which you review the actual deletion proposal.

Read the preview count and target information before typing the confirmation phrase. An unexpected
count is a stop condition: cancel, correct the scope or selection, and request a new preview. Copying
a phrase from an older operation is unsafe because each preview has its own lifecycle. The phrase is
an intentional acknowledgement of the proposal you are viewing, not a reusable password.

The data can change after preview. In a controlled test, another actor might update one of the three
entries before execution. Version-aware deletion must then distinguish the unchanged targets from
the stale one. For example, a result reporting two deletions and one conflict is not an unexplained
failure of the entire operation; it is an outcome requiring review of the remaining entry. Do not
assume this example will always produce exactly those counts on a changing system.

After execution, refresh with the same scope and relevant filters. Inspect any survivor before deciding
whether it should still be deleted. If so, obtain a fresh preview based on its current state and repeat
the approval check. A preview that expired before use also requires a fresh review. A consumed preview
cannot authorize a second execution, even if the original result is no longer visible in the browser.

If the network fails at submission, do not repeatedly press execute. Inspect current entries and any
available outcome information, then decide whether a new operation is necessary. Lack of a response
is not proof that the first request did nothing.

**Completion record:** approved scope, previewed count, reported deletion/conflict counts, and the
disposition of remaining entries. Do not include entry values in the record.

**Complete when:** the approved deletion has been attempted once, its results are accounted for,
and any remaining items are understood rather than assumed deleted.

**If something goes wrong:** an expired preview requires a fresh preview and another review. A
stale target must be assessed at its current version. A consumed preview cannot be replayed. Do not
blindly resubmit after an uncertain response; inspect the current state first.

## 9. Manage a counter throughout its lifecycle

**Goal:** maintain an exact counter value and lifetime, then remove the counter when finished.

**Before starting:** counter-mutation permission and an approved key are available.

1. Open **Counters** and create a counter with an exact decimal value.
2. Locate it and verify the displayed value and version.
3. Apply a positive adjustment and verify the new value.
4. Apply a negative adjustment and verify the result again.
5. Set a future expiry; if the counter should persist, remove the expiry before it elapses.
6. Delete the counter using the current version and required key confirmation.
7. Refresh and confirm that the counter is absent.

### Screenshots along the journey

![Create counter dialog with exact-value and adjustment paths](screenshots/counter-open-counter-creation-element.png)

*Creation steps — Specify namespace and key, then deliberately choose an exact starting value or creation by signed adjustment. TTL is a separate lifetime choice.*

![Manage counter dialog showing an exact value of 42 and adjustment of 8](screenshots/counter-apply-a-positive-adjustment-element.png)

*Adjustment controls — Set exact value and Apply adjustment are different operations. The dialog retains its inputs; this image does not show the updated counter row, so verify the returned value and current row before considering a repeat.*

### Worked explanation: exact values and non-repeatable adjustments

Create a disposable counter named `processed-items` in `journey-demo`. Enter `100` in **Exact decimal
value** and choose **Set exact value**. Read the result, then locate the counter and verify the value
and current version. For an existing counter, setting an exact value means replacing the current
number with the supplied number; it is not the same as adding that number.

Open **Manage processed-items**, enter `25` in **Signed adjustment**, and choose **Apply adjustment**.
With no concurrent activity, the expected result is `125`. Next apply `-5` and expect `120`. These
simple numbers make the arithmetic easy to check. An unexpected result requires investigation of
concurrent changes or the operation outcome before any further adjustment.

Adjustments are especially important when a response is lost. If `+25` committed but the browser did
not receive its response, repeating `+25` can add another 25. Refresh and assess the current state
instead of interpreting a timeout as a failed increment. Creating a missing counter through **Create
by adjustment** is also a distinct operation: use it only when creation is part of the task.

Counter inputs preserve signed 64-bit integers, including values too large to represent exactly in
some spreadsheets or general numeric tools. Fractions are not valid counter values. Boundary tests
belong in disposable environments; operators should not move a production counter near a limit just
to test overflow. Preserve the exact decimal string when transferring an operationally important value.

Enter a future TTL, select **Set counter TTL**, and verify the expiry. Choose **Make counter persistent**
before expiry if that is the required final lifetime. To finish the demonstration, enter the exact
key in **Confirm counter key**, choose **Delete current version**, and refresh to confirm absence.
For several approved counters, use **Preview selected counter deletion** and review its own results.

**Completion record:** starting value, requested adjustments, observed final value, lifetime changes,
and final existence state. Distinguish the adjustment submitted from the value observed afterward.

**Complete when:** the observed values match the intended arithmetic and the final state matches
the requested lifecycle. Values use the signed 64-bit range; do not convert them to rounded numbers
when recording or transferring them.

**If something goes wrong:** fractional values, overflow, and underflow are not valid adjustments.
For stale versions, refresh and reassess before retrying. Selected bulk counter deletion follows the
same preview, exact-confirmation, and result-review discipline as journey 8.

## 10. Investigate and release a lock

**Goal:** investigate an active lease and, when authorized, release the current lock safely.

**Before starting:** lock-inspection and forced-release permissions are available, and the lock's
owner or responsible service has been consulted before an operational release.

1. Open **Locks** and locate the relevant lock.
2. Inspect its lease, version, and fencing information.
3. Reveal the owner only if necessary and permitted; hide it after inspection.
4. Start forced release, review the target, and enter the required key confirmation.
5. If the lease or version has changed, stop and refresh the lock information.
6. Reassess whether release is still appropriate, then confirm against the current version.
7. Verify the release result and refresh to confirm that the target lock is no longer active.

### Screenshots along the journey

![Exact-key confirmation for releasing the current lock version](screenshots/lock-enable-release-for-exact-key-element.png)

*Release confirmation — The key has been entered and Release current version is enabled. The warning explains that renewal or reacquisition can invalidate this confirmation; it is not permission to release any later lease.*

![Locks page confirming release and showing no active locks](screenshots/lock-lock-owner-remains-masked-and-stale-release-is-rejected-before-current-release-viewport.png)

*Result check — The console reports release after exact-version confirmation and shows no active locks in this example. This final state does not display the earlier stale-release rejection.*

### Worked explanation: identifying the lease you are about to release

Assume an operator reports that a background job appears blocked by a lock. A lock's presence alone
does not establish that it is abandoned: the owner may still be doing legitimate work. Start by
identifying the setup, namespace, and key, then inspect the active lease and version in **Manage**.
Correlate this information with the responsible service through your approved operational process.

**Reveal owner** is available only when permitted and should be used only when it helps the diagnosis.
The owner identifier may itself be sensitive. Supply a meaningful optional reason, inspect the
information, and choose **Hide owner** once it is no longer needed. A screenshot for a ticket should
show the relevant masked metadata, not the owner token.

Choose **Force release** only after deciding that intervention is authorized. The confirmation dialog
asks for the lock key and applies to the current observed lock state. Suppose the owner renews or the
lease changes while you are reading that dialog. A stale-release rejection is the expected safeguard:
the state on which you based the decision is no longer current.

Refresh the lock and repeat the operational assessment. Do not merely reopen the dialog and release
the new version automatically; it may represent active work or a replacement owner. If release is
still justified, enter **Confirm lock key**, choose **Release current version**, and verify the result.
Then inspect the active-lock list again. If a lock reappears, compare its current lease information:
another acquisition after a successful release is different from the original release failing.

**Completion record:** target identity, non-sensitive justification, observed lease/version metadata,
whether stale state was encountered, and the confirmed release or decision not to release. Coordinate
follow-up with the service owner; releasing a lock does not by itself prove the blocked job recovered.

**Complete when:** the release decision applies to the current lock state and its outcome is confirmed.

**If something goes wrong:** a stale-release rejection protects a changed lease; it is not a reason
to bypass version checks. A service may acquire a new lock after release, so distinguish a new lease
from a failed release. Forced release can affect concurrent work and requires operational judgment.

## 11. Complete an owner-controlled lock lifecycle

**Goal:** acquire, maintain, and release a lock using the same authorized owner identity.

**Before starting:** the **Advanced** workspace and the required owner-lock operations are available.
Use a dedicated demonstration lock unless this is an approved operational task.

1. Enter the lock namespace, key, owner token, and positive lease duration.
2. Acquire the lock and inspect the returned acquisition result.
3. Check ownership before performing work that depends on the lease.
4. Renew the lease when required and verify the renewal result.
5. Release the lock by owner and verify the result.
6. Clear sensitive state, including the owner-token input.

### Screenshots along the journey

![Advanced owner-lock controls after clearing sensitive state](screenshots/backend-advanced-workspace-exercises-complete-backend-service-parity-viewport.png)

*Owner lifecycle controls — The lower Advanced panel provides namespace, key, lease TTL, reentrancy, fencing, acquisition, renewal, ownership checking, release, and sensitive-state cleanup. This capture identifies the controls after application cleanup, with the owner input cleared; it does not display an ownership verdict or returned fencing token.*

### Worked explanation: owning a lease is a temporary condition

Use an approved demonstration key such as `maintenance-lease`. In Advanced, enter its namespace and
key, an owner token supplied or generated through the approved process, and a positive lease duration.
The token is not a descriptive label to put in a screenshot; it identifies the owner for protected
operations and should be handled as sensitive data.

Choose **Acquire lock** and inspect the actual result. A completed request can report that acquisition
did not occur because another owner holds the lock. Only an affirmative acquisition outcome permits
you to proceed as the owner. Where fencing information is returned, preserve it for the authorized
workflow that uses it; merely viewing a fencing token does not make unrelated downstream work safe.

Choose **Check ownership** to confirm the current state. Ownership at acquisition time is not a
permanent guarantee: the lease can expire, be released, or be changed before the next operation.
For a demonstration, perform the check and renewal within the displayed lease window. **Renew lock**
must return a confirmed renewal; submitting a renewal request without reading its response is not
evidence that the lease was extended.

When the protected task is finished, choose **Release by owner** and read the release result. If
ownership has already been lost, stop and reassess rather than substituting forced release. An
owner-based release and an administrative forced release have different authorization and safety
purposes, even though both can lead to a lock disappearing.

Finally choose **Clear sensitive state** and verify that the owner-token input and sensitive results
are cleared. If the task created other temporary data, clean that up separately; releasing a lock
does not roll back work performed while it was held.

**Completion record:** acquisition, ownership check, renewal, and release outcomes, with the target
identity and observation times. Record no owner-token value.

**Complete when:** acquisition and any renewal were confirmed, the lock was released, and owner
information has been cleared from the workspace.

**If something goes wrong:** a failed acquisition means you must not assume ownership. If ownership
is lost or the lease expires, stop the dependent work and reassess. Do not use forced release merely
to work around a failed owner operation.

## 12. Publish and receive a message

**Goal:** demonstrate delivery from an authorized publication to an active subscription, then stop cleanly.

**Before starting:** Pub/Sub capabilities and permissions are available; use an approved channel and
non-sensitive demonstration payload unless the actual task requires protected content.

1. Open **Pub/Sub**, enter the channel and supported buffer settings, and start a subscription.
2. Confirm that the subscription is active before publishing.
3. Enter the publication channel, payload, and optional content type, then publish.
4. Check publication acceptance and separately confirm the message appears in the subscription.
5. Reveal the received payload only when needed; inspect it and hide it again.
6. If an interruption occurs, inspect connection status and use the offered reconnect or resume behavior.
7. Stop the subscription and confirm that it is no longer active.

### Screenshots along the journey

![Connected subscription with received message metadata and masked payload](screenshots/pubsub-deliver-masked-message-metadata-viewport.png)

*Receipt check — The connected events subscription contains a received message with timestamp and byte count while its payload stays masked. Publication acceptance and receipt are separate checks; the Publish panel explicitly warns of the distinction.*

![Explicit payload viewer with Hide payload control](screenshots/pubsub-reveal-payload-explicitly-viewport.png)

*Authorized inspection — A separate Revealed payload panel displays the actual message content after explicit reveal, with a Hide payload action. Hide it when inspection is complete.*

### Worked explanation: separating acceptance, observation, and retention

For a safe demonstration, use channel `journey-demo-events` and a harmless payload such as
`{"event":"journey-check","sequence":1}`. Choose a bounded buffer suitable for the exercise; **Buffer
entries** accepts 1–500 entries in the current UI. Larger buffers are still bounded, not durable message
storage. Start the subscription first and confirm its active state before publishing the demonstration
message, so there is an active observer for the publication.

In the publication panel, enter exactly the intended **Publish channel**. A different spelling sends
to a different channel. Enter the payload and, if appropriate, `application/json` as the optional
content type. Read the byte-limit feedback rather than assuming character count equals encoded size,
especially with non-ASCII text. **Publish** can remain disabled when the supplied values exceed the
advertised limits.

After publishing, check two separate outcomes. First, did PostgreSQL accept the publication? Second,
did the active subscription receive the intended message? The acceptance notice proves only the
first. Inspect the retained-message metadata to identify the observation, then use its **Reveal
payload** action to inspect content when permitted. Choose **Hide payload** after the check.

During an interruption, the console can reconnect using the available subscription/resume state.
That does not turn PostgreSQL Pub/Sub into a durable queue. A bounded retained buffer can also lose
older entries as capacity is reached, and a closed session or setup ends the console subscription.
If a message is absent, examine channel, subscription state, timing, and notices before deciding to
publish again. Repeating a publication can create an additional delivery, so the harmless sequence
field helps distinguish deliberate repetitions in this demonstration.

Choose **Stop subscription** and verify the active subscription has ended. A final retained visual
result or a previously received message is not proof that the transport is still open; use the
displayed subscription state. Clear payload inputs or leave the workspace securely when done.

**Completion record:** channel, a non-sensitive correlation description, acceptance and receipt
outcomes, any interruption or loss notice, and confirmation that the subscription was stopped.

**Complete when:** the intended message has been observed by the subscriber and the subscription
has been explicitly stopped.

**If something goes wrong:** accepted publication is not proof of subscriber delivery. These
subscriptions are bounded and non-durable; do not assume disconnected subscribers receive every
missed message. Inspect buffer, connection, and resume information before deciding whether a new
publication is safe—republishing may create another message.

## 13. Recover interrupted live monitoring

**Goal:** restore trustworthy live information after a connection interruption without losing the working page.

**Before starting:** live monitoring is active. Deliberately interrupt connectivity only in an approved
test environment; in ordinary use, begin when a real interruption is reported.

1. Open **Monitoring** and confirm that live updates are arriving.
2. When connectivity is interrupted, observe the disconnected, retrying, or stale indication.
3. Treat retained values as the last known state, not fresh measurements.
4. Restore connectivity or wait for the service operator to resolve the outage.
5. Observe reconnection without reloading the page and verify that updates resume.
6. Check that resumed events are not being displayed as duplicate new events.

### Screenshots along the journey

![Monitoring after reconnection with Live metrics connected status](screenshots/monitoring-reconnect-metrics-automatically-when-browser-connectivity-returns-viewport.png)

*Recovery check — This is the recovered state: Live metrics connected appears above the monitoring panels and notifications remain available. It does not show the earlier outage. Confirm fresh samples and event handling as well as the connection label.*

### Worked explanation: stale data can look reassuring

Open Monitoring for the selected setup and observe several updates before investigating an
interruption. This establishes that the live view was functioning and gives you an observation time
for the last known good state. Depending on the surface, updates use server-sent events or WebSocket
connections. The transport names are less important to the user than knowing whether the displayed
information is still being refreshed.

When connectivity is lost, old values can remain visible so that useful context is not discarded.
They must be read together with the disconnected, retrying, or stale indication. For example, a
previously healthy value that remains on screen throughout an outage is not a new health check.
Do not copy it into an operational report without qualifying it as the last observed value.

For an actual outage, follow the operator's network or service recovery process. For an acceptance
exercise, arrange the interruption on a disposable deployment, preserving a way to restore access.
Do not disconnect production simply to follow this guide. Once connectivity returns, allow the
existing page to reconnect and observe whether the notice clears or changes appropriately.

Confirm recovery from fresh observations, not merely from the disappearance of a warning. Check that
new samples or events arrive and that resumed events are not counted as fresh duplicates. **Refresh
monitoring** can obtain the available current snapshot, but a successful snapshot request alone does
not prove that continuous live updates have resumed. Keep those two checks separate when diagnosing
a partially recovered connection.

**Completion record:** last known good observation, interruption indication, restoration time, and
evidence of fresh updates after recovery. If the view remains stale, record the unresolved condition
and escalate it rather than marking the journey complete.

**Complete when:** the live connection is healthy again and the displayed information is current.

**If something goes wrong:** persistent retrying or stale state requires investigation of service
availability, authentication, and the network path. Do not label the system healthy merely because
old values remain visible.

## 14. Perform batch cache maintenance

**Goal:** inspect and maintain multiple explicitly identified entries through the Advanced workspace.

**Before starting:** the required Advanced capabilities and permissions are available. Approve the
complete target list, especially when operations span namespaces.

1. Open **Advanced**, confirm the setup, and check existence for a relevant entry.
2. Run a scoped backend scan and review the returned entries.
3. Retrieve a batch using the input format described in the UI: one namespace/key pair per line,
   separated by a tab.
4. Prepare batch-set JSON using the supported typed values, set modes, TTL settings, and expected
   versions shown by the contract and form guidance.
5. Submit the batch and inspect each result; do not assume the entire batch is an all-or-nothing change.
6. Re-read the affected entries to confirm their resulting values or metadata.
7. If deletion is part of the approved task, enter only the exact intended namespace/key pairs,
   execute batch deletion, inspect the result, and confirm absence.
8. Clear sensitive inputs and results when finished.

### Screenshots along the journey

![Full Advanced workspace with scan and batch maintenance panels](screenshots/backend-advanced-workspace-exercises-complete-backend-service-parity-element.png)

*Workspace reference — The full-height capture places Existence and scan, Batch get, Batch set, and Cross-namespace batch delete in context. Inputs and result panels have been cleared by the application's cleanup action, not altered for the screenshot. Follow the input guidance and inspect each operation's results during your work; the final Lock operation completed banner is not a batch-success receipt.*

### Worked explanation: explicit inputs and per-item outcomes

Advanced operations are intended for users who understand the scope and contract of the requested
operation. Start with a read-only existence check and a narrow scan. A backend scan or batch read
can expose values, unlike an ordinary metadata list; treat the result panel as sensitive even if the
input looked like a harmless list of keys. Clear results after the investigation.

For batch get or delete, each line identifies a namespace and key separated by one actual tab. Spaces
that merely look like a tab do not express the same input. For example, the pair `journey-demo` and
`sample-entry` should be entered in the UI's required tab-separated form. Review every line, remove
unintended targets, and check the selected setup above the workspace. Explicit namespaces mean a
batch can cross the namespace you were previously browsing.

Batch set requires a JSON array of typed entry requests. A disposable single-item example is:

```json
[
  {
    "namespace": "journey-demo",
    "key": "batch-sample",
    "value": { "type": "STRING", "text": "demonstration value" },
    "ttlMillis": 600000,
    "setMode": "ONLY_IF_ABSENT",
    "expectedVersion": null,
    "returnPreviousValue": false
  }
]
```

This example requests creation only if absent, with a ten-minute TTL, and does not request the
previous value. It is not a template for updating an existing production key: updating may require
a different set mode and an observed expected version. Keep the JSON valid and use the documented
value representation for the actual type; a numeric-looking string is not automatically a LONG value.

After submission, inspect each returned result. A batch request reaching the server does not prove
that every entry changed, and a non-applied item does not justify replaying already-applied items.
Re-read the target metadata or authorized values and create a smaller follow-up request only after
understanding the remaining work. Requests that return previous values can expand the sensitive
content shown in the workspace; request that information only when it is needed.

**Delete entry batch** acts on the explicit input and must not be assumed to have the preview workflow
from journey 8. Review the list before pressing it, inspect the deletion result, and verify absence.
When complete, choose **Clear sensitive state**. Keep this Advanced workflow separate from routine
previewed administration so the user does not carry the wrong safety assumptions between them.

**Completion record:** the approved target scope, requested operation, per-item applied or non-applied
outcomes, verification performed, and any remaining work. Keep payloads, previous values, and owner
tokens out of the record, even when the workspace displayed them during the operation.

**Complete when:** the outcome of every requested item is understood and the resulting state has
been checked. Unlike the preview workflow in journey 8, Advanced batch deletion must not be assumed
to provide the same preview/confirmation process; review its explicit input before submission.

**If something goes wrong:** correct malformed input before submission. For partial success or
conflicts, reassess only the affected items instead of blindly replaying the entire batch.

## 15. Inspect sensitive data and leave safely

**Goal:** inspect an authorized value only for as long as needed, without retaining it in ordinary console state.

**Before starting:** reveal permission and a legitimate reason to inspect the selected value are available.

1. Locate the entry and confirm its setup, namespace, and key before revealing anything.
2. Request reveal, entering a reason when appropriate.
3. Inspect the value using the available type-appropriate view.
4. Copy only if required, and only to an approved destination.
5. Choose **Hide value**, or leave the view and confirm the reveal is removed. Respect any automatic
   hide window displayed by the console.
6. End the local session, or follow the trusted identity provider's sign-out process, when work is complete.
7. Confirm that protected content is no longer visible. Clear any user-created copies according to
   the applicable data-handling policy.

### Screenshots along the journey

![JSON reveal with readable tree content and automatic-hide notice](screenshots/entry-default-valid-json-to-a-structured-tree-viewport.png)

*Inspection state — The revealed JSON is readable in the selected Tree view. Formatted text and Raw UTF-8 are alternative views, with an automatic-hide notice and an explicit Hide value action.*

![Entry viewer returned to Value hidden state](screenshots/entry-remove-a-value-after-explicit-hide-viewport.png)

*Cleanup state — In this separate string-entry example, Value hidden and Reveal value replace the transient viewer. Hiding removes the console reveal, not copies previously sent to the clipboard or another application.*

### Worked explanation: reveal is a temporary privilege, not normal browsing

Assume you are investigating a value-format problem. First use metadata to establish whether the
entry is the right one: setup, namespace, key, type, version, and lifetime may answer part of the
question without revealing content. Request reveal only when the content is genuinely needed. A
meaningful optional reason describes the task, such as investigating a format mismatch, without
putting a sensitive value into the reason field itself.

Once revealed, use the view suited to the stored type. A JSON value can be inspected as a structured
tree, formatted text, or raw UTF-8 where offered. These views help distinguish the stored content from
its presentation. Invalid JSON is a content condition to investigate; switching views does not repair
the stored value. Byte-oriented and numeric views likewise need to be interpreted according to their
declared type, not according to whichever display looks most familiar.

**Copy revealed value** is a deliberate transfer out of the transient viewer. Before selecting it,
identify the approved destination and consider clipboard synchronization or history on the workstation.
The console's hide action cannot remove a value already pasted into another application, a shared
document, or clipboard history. If copying is unnecessary, inspect the value in place and avoid creating
that additional copy.

Observe the displayed automatic-hide window and choose **Hide value** as soon as the task is finished.
Navigation, scope changes, and other lifecycle events can also remove the reveal. Check that the
sensitive viewer has cleared rather than assuming that an elapsed timer or a hidden tab proves it.
Then end the session through the applicable authentication mode.

If you suspect a leak, describe the action, affected surface, time, and whether content remained visible.
Do not demonstrate the leak by pasting the content into a support conversation. Authorized security
verification can check storage, URLs, console output, logs, and reveal/hide behavior using designated
test data. Ordinary users should report the observation through the approved channel and preserve only
non-sensitive evidence.

**Completion record:** why inspection was necessary, which representation was used, whether copying
occurred to an approved destination, and how the transient reveal and external copies were handled.

**Complete when:** inspection is finished, the transient reveal is gone, and any copies outside
the console have been handled deliberately.

**If something goes wrong:** unexpected persistence or exposure is a security issue. Stop copying
the content, avoid further disclosure, and report the affected action and time through
the approved incident channel. Browser-storage and ordinary-log leakage checks belong to controlled
acceptance verification; do not disclose real sensitive values while reporting a suspected leak.

## 16. End an active operational session cleanly

**Goal:** finish work with no unintentionally active subscriptions, exposed values, or protected local session.

**Before starting:** identify any open subscriptions, live monitoring, and outstanding operations.
Service shutdown is a separate operator action, not an ordinary console-user control.

1. Finish or explicitly resolve outstanding mutations and inspect their outcomes.
2. Stop active Pub/Sub subscriptions and hide or clear revealed content.
3. End the local session, or follow the trusted proxy/identity provider's sign-out procedure.
4. Confirm that protected content is removed and the ended session is not restored by reload.
5. If a service shutdown is planned, the service operator performs it through the approved runtime
   procedure and verifies that transports, subscriptions, and database pools close.
6. After a restart, establish a valid new session and confirm the required setup state before resuming work.

### Screenshots along the journey

![Pub/Sub page confirming subscription stopped and retained messages discarded](screenshots/pubsub-return-to-subscribe-after-stop-viewport.png)

*Step 2 — The notice confirms the subscription stopped and retained messages were discarded; Start subscription is available again. You are still in the console and must separately end the user session.*

![Unauthenticated connection gate after ending the local session](screenshots/authentication-successful-logout-returns-to-connection-gate-viewport.png)

*Steps 3–4 — For local-token authentication, the return to the connection gate is the visible session-ending outcome. It does not prove service shutdown or database-pool cleanup; those require separate operator verification.*

### Worked explanation: three different endings

Distinguish ending a task, ending a user session, and stopping the service. Finishing an edit does
not stop an active subscription. Stopping a subscription does not sign you out. Closing a browser
does not stop the management server or necessarily end a centrally managed identity session. A
clean ending deliberately handles each resource that belongs to the work you performed.

For an ordinary user departure, first resolve any pending operation. If a mutation's response was
lost, inspect the current state and record any uncertainty rather than abandoning the page and
assuming failure. Stop subscriptions, hide revealed values, clear Advanced sensitive state where
used, and clean up disposable demonstration items that should not remain. Do not delete application
data solely because the demonstration version of a journey ends with deletion.

Next end the local session or follow the identity provider's procedure. Verify the appropriate
authentication outcome. A failed logout notice means you still need to act; a hidden window is not
proof that the server invalidated the session. For a shared workstation, also deal with any approved
exports or clipboard copies under the organization's handling policy.

A planned service shutdown is a coordinated operator workflow. Establish the maintenance window,
notify affected users as required, stop new work through the approved process, and stop the service
using its runtime procedure. The operator checks transport closure, subscriptions, pool cleanup,
and any configured write-behind drain outcome. Ordinary console users cannot certify those backend
conditions merely by seeing a disconnected indicator. A controlled integration test may verify
resource gauges return to baseline; the production operator uses the appropriate operational evidence.

After restart, do not assume the old browser session or setup selection is still authoritative.
Authenticate through the current valid mechanism, inspect the setup's registered/connected state and
health, and verify scope before resuming mutations. Record any pending task that was not completed
before shutdown so it can be reassessed, not blindly replayed.

**Completion record:** the task's final outcome, subscription and sensitive-state cleanup, session
ending, and—only when applicable—the operator's separate shutdown/restart confirmation.

**Complete when:** user activity has ended securely; for a planned shutdown, the operator has also
verified resource cleanup. Do not equate closing a browser tab with shutting down the management service.

**If something goes wrong:** retry failed local logout and verify its result. Escalate persistent
connections or incomplete shutdown using operational diagnostics, without including credentials or payloads.

## Completion and review checklist

Apply these checks to each relevant journey rather than treating them as isolated user journeys:

- **Correct scope:** identity, role, setup, namespace, and target were checked before acting.
- **End-to-end outcome:** the final visible state matches the user's intent, not merely a clicked button.
- **Safe failure handling:** conflicts, expired previews, access denial, and uncertain responses were
  handled without blind retries or bypassing safeguards.
- **Data handling:** reveals were explicit and temporary; credentials and values were not exposed in
  shared logs, links, or exports not intended to contain them.
- **Accessibility:** the workflow remains usable with the keyboard, with meaningful focus and readable
  results at the supported desktop layout.
- **Cleanup:** subscriptions, temporary values, demonstration data, and sessions were dealt with as required.
- **Evidence:** record the journey name, outcome, observation time, and non-sensitive supporting evidence.
  A single screenshot does not by itself prove every step of the process.

## Screenshots and further guidance

Use the [screenshot gallery](screenshots/index.html) to find images by feature and behavior. Images are
published in a flat directory with descriptive filenames. The gallery is generated by a complete
passing browser run and may not exist in a fresh checkout until evidence is generated.

The 28 inline figures cover all 16 journeys, organized by the user's process rather than test IDs.
The gallery's captures illustrate individual verified states. This guide groups user activities into
complete processes; it does not claim that each journey is represented by a new sequential screenshot
walkthrough or by one dedicated automated test.

Related documents:

- [Screenshot publication and regeneration](screenshots/README.md)
- [Management server operations](../../docs/PEEGEEQ_CACHE_MANAGEMENT_OPERATIONS.md)
- [Management UI implementation plan](../../docs/design/PEEGEEQ_CACHE_MANAGEMENT_UI_IMPLEMENTATION_PLAN.md)
- [Browser verification and screenshot evidence](../../docs/design/PEEGEEQ_CACHE_PLAYWRIGHT_IMPLEMENTATION_PLAN.md)
