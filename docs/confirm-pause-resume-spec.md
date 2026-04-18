# Confirm / Pause / Resume — As-Built Specification

**Status:** Implemented and live-tested on branch `cycle/workflow-suspend-resume`.
**Date:** 2026-04-18
**Branch:** 15 commits ahead of main, ~70 files, ~9000+ lines.

This document describes the suspend/resume feature as actually built and verified — not the theoretical design spec from the planning phase. The theoretical specs at `docs/specs/workflow-suspend-resume.md`, `docs/specs/confirm-workflow-step.md`, and `docs/specs/operator-pause.md` describe the intended design; this document describes what shipped and what didn't.

---

## 1. What it does

A workflow step plugin can **suspend** an execution, releasing its JVM thread. The execution parks in a `waiting` state in the database. An external event (human approval, API call, timeout) delivers a resume payload. A polling worker claims the execution, rehydrates the context, and re-enters the engine to continue from where it left off.

Two first-class consumers ship:

### 1.1 Confirm step plugin

A built-in `ConfirmWorkflowStep` plugin (type: `confirm`) that pauses a workflow pending human approval. The operator sees a confirmation panel in the execution detail page with Approve/Deny buttons.

**Job definition:**
```xml
<command>
  <step-plugin type="confirm">
    <configuration>
      <entry key="message" value="Deploy v2.1.0 to production?"/>
      <entry key="timeout" value="8h"/>
      <entry key="timeoutAction" value="deny"/>
    </configuration>
  </step-plugin>
</command>
```

**Configuration properties:**
| Property | Type | Default | Description |
|---|---|---|---|
| `message` | string (required) | — | Prompt shown to confirmers |
| `timeout` | duration | `24h` | Max wait (e.g., `1h`, `30m`, `8h`) |
| `timeoutAction` | `deny` \| `approve` \| `fail` | `deny` | What happens on timeout |
| `requiredConfirmerRoles` | comma-list | — | Roles the confirmer must hold (not yet enforced) |

### 1.2 Operator pause (API only, no UI yet)

Any running multi-step execution can be paused at the next step boundary via `POST /api/{v}/execution/{id}/pause` and resumed via `POST /api/{v}/execution/{id}/resume`. The pause takes effect cooperatively — the current step finishes, then the execution parks before the next step begins.

---

## 2. End-to-end flow (verified live)

```
alice runs job: echo BUILD → confirm → echo DEPLOY
  ↓
Step 1 (BUILD) runs normally → "BUILD-OK"
  ↓
Step 2 (confirm) calls context.suspend(request)
  → SuspendedStepResult stored in pendingSuspension field
  → StepPluginAdapter detects it, returns to engine
  → Engine rule STEP_SUSPENDED_END_WORKFLOW fires → loop exits
  → ExecutionJob detects thread.isSuspended()
  → Log writer suspend-closed (no ^END^ footer)
  → DB: status='waiting', serverNodeUUID=NULL, checkpoint saved
  → Thread released
  ↓
Execution visible in UI as "waiting" with orange pause icon
Confirmation panel slides in on execution detail page
  ↓
bob clicks Approve (or calls POST /api/{v}/execution/{id}/confirm)
  → ExecutionConfirmation audit row written
  → resume_ready=true, resume_payload=ConfirmationPayload JSON
  ↓
ExecutionResumeService polling worker (2s tick)
  → Finds waiting + resumeReady row
  → Atomic claim: UPDATE SET serverNodeUUID=me, status='running'
  → Rehydrates ExecutionContextImpl from checkpoint
  → Reopens log writer in append mode (no second header)
  → Enters EngineWorkflowExecutor.executeWorkflowResume
    Phase A: re-invokes confirm step with resume payload
      → Plugin reads ConfirmationPayload, logs "Confirmed by admin"
      → Returns success
    Phase B: runs remaining steps via normal engine
      → Step 3 (DEPLOY) dispatched to local node → "DEPLOY-OK"
  → Terminal write: status='succeeded', dateCompleted set
  → Suspend columns cleared (I11)
```

---

## 3. API endpoints

All endpoints require authentication. Browser sessions use CSRF token headers (`X-RUNDECK-TOKEN-KEY`, `X-RUNDECK-TOKEN-URI`). API tokens use `X-Rundeck-Auth-Token`.

### 3.1 Confirmation

| Method | URL | Description |
|---|---|---|
| POST | `/api/{v}/execution/{id}/confirm` | Submit a decision |
| GET | `/api/{v}/execution/{id}/confirm/status` | Get confirmation state |
| GET | `/api/{v}/execution/{id}/confirmations` | List audit entries |

**POST /confirm** request body:
```json
{"decision": "approve", "comment": "LGTM after CI passed"}
```

**POST /confirm** response (200):
```json
{"resumeReady": true, "confirmationId": 1, "decision": "approve"}
```

**GET /confirm/status** response:
```json
{
  "waiting": true,
  "currentStatus": "waiting",
  "message": "Deploy v2.1.0 to production?",
  "decisionSet": ["approve", "deny"],
  "waitStartedAt": "2026-04-18T09:49:40Z",
  "waitTimeoutAt": "2026-04-18T17:49:40Z",
  "callerCanConfirm": true
}
```

Error responses: 404 (not found), 409 (not waiting / wrong type / state changed), 403 (not authorized).

### 3.2 Operator pause

| Method | URL | Description |
|---|---|---|
| POST | `/api/{v}/execution/{id}/pause` | Request pause at next step boundary |
| POST | `/api/{v}/execution/{id}/resume` | Resume an operator-paused execution |
| GET | `/api/{v}/execution/{id}/pause/status` | Get pause state |

### 3.3 ACL actions

| Action | Resource | Used by |
|---|---|---|
| `confirm` | `execution` | Confirm step approve/deny |
| `pause` | `execution` | Operator pause/resume |

---

## 4. Database schema

### 4.1 Execution table additions (9 columns)

| Column | Type | Description |
|---|---|---|
| `checkpoint_data` | CLOB | JSON blob of ExecutionCheckpoint |
| `suspend_metadata` | CLOB | JSON of suspend request metadata |
| `wait_started_at` | TIMESTAMP | When execution entered waiting |
| `wait_timeout_at` | TIMESTAMP | Absolute timeout deadline |
| `last_resumed_at` | TIMESTAMP | When last resume claim succeeded |
| `resume_ready` | BOOLEAN | True when approval/event delivered |
| `resume_payload` | CLOB | JSON of ResumePayload (polymorphic) |
| `resume_attempt_count` | INTEGER | Failed resume attempts; exhausts at 3 |
| `pause_requested` | BOOLEAN | Set by POST /pause API |

### 4.2 ExecutionConfirmation table (new)

| Column | Type | Description |
|---|---|---|
| `id` | BIGINT | Primary key |
| `execution_id` | BIGINT | FK to execution |
| `confirmed_by` | VARCHAR(255) | User who confirmed |
| `confirmer_roles` | VARCHAR(1024) | Roles at confirmation time |
| `decision` | VARCHAR(64) | "approve" or "deny" |
| `comment` | TEXT | Free-text comment |
| `confirmed_at` | TIMESTAMP | When confirmed |
| `timeout` | BOOLEAN | True if synthetic timeout |
| `step_context` | VARCHAR(64) | Which step in the workflow |

Rows are **permanent** — not cleared on execution completion.

---

## 5. Plugin architecture

### 5.1 How a step plugin suspends

```java
// In StepPlugin.executeStep():
StepExecutionContext ctx = (StepExecutionContext) pluginContext.getExecutionContext();

ResumePayload payload = ctx.getResumePayload();
if (payload == null) {
    // First invocation: request suspension
    ctx.suspend(SuspendRequest.builder()
        .token(UUID.randomUUID().toString())
        .reason("awaiting confirmation")
        .timeoutMs(TimeUnit.HOURS.toMillis(8))
        .metadata(Map.of("type", "confirmation", "message", "Approve?"))
        .build());
    return; // StepPluginAdapter detects pendingSuspension
}

// Resume invocation: process the payload
if (payload instanceof ConfirmationPayload) {
    ConfirmationPayload cp = (ConfirmationPayload) payload;
    if ("approve".equals(cp.getDecision())) {
        return; // success
    }
    throw new StepException("Denied", ConfirmDenied);
}
```

### 5.2 Key mechanism: pendingSuspension bridge

`StepPlugin.executeStep()` returns `void`, so a suspended step can't return a `SuspendedStepResult` directly. The bridge:

1. `ExecutionContextImpl.suspend(request)` stores the result in a `volatile pendingSuspension` field
2. `StepPluginAdapter.executeWorkflowStep()` checks `getPendingSuspension()` after `plugin.executeStep()` returns
3. If non-null, returns the `SuspendedStepResult` to the engine instead of the default success result

### 5.3 ResumePayload subtypes

| Type discriminator | Class | Used by |
|---|---|---|
| `confirmation` | `ConfirmationPayload` | Confirm step plugin |
| `operator-resume` | `OperatorResumePayload` | Operator pause |

Registered via `@JsonSubTypes` on the `ResumePayload` interface. Third-party plugins can register additional subtypes via `JacksonSubtypeRegistrar` Spring beans.

### 5.4 Operator pause mechanism

`ExecutionContextImpl` carries a transient `pauseCheckSupplier` (Supplier<Boolean>) set by `ExecutionService.executeAsyncBegin`. `StepCallable.apply()` checks it before each step invocation. If true, synthesizes a `SuspendedStepResult` with `metadata.type=operator-pause` without invoking the step.

---

## 6. Resume worker

`ExecutionResumeService` is a Grails service with `@Scheduled` polling (requires `@EnableScheduling` on the Application class).

**Poll cycle (every 2 seconds):**
1. Query: `status='waiting' AND resume_ready=true AND serverNodeUUID IS NULL`
2. Atomic claim: `UPDATE SET serverNodeUUID=me, status='running', last_resumed_at=now() WHERE ...`
3. On successful claim (1 row): call `resumeExecution(execution)`

**Resume execution:**
1. Reload execution in a single Hibernate session (avoids lazy proxy issues)
2. Parse checkpoint JSON → `ExecutionCheckpoint`
3. Parse resume payload → `ResumePayload` (polymorphic Jackson)
4. Rebuild auth context from frozen user + roles via `BaseAuthContextProvider`
5. Reopen log writer in append mode via `LogFileStorageService.getLogFileWriterForResume`
6. Build `ExecutionContextImpl` with resume payload + suspend metadata
7. Build workflow item from execution's workflow data
8. Call `EngineWorkflowExecutor.executeWorkflowResume` (two-phase: Phase A re-invokes suspended step, Phase B runs remaining steps via normal engine)
9. On completion: write terminal state directly in the same Hibernate session

**Timeout sweep (every 30 seconds):**
- Finds `status='waiting' AND resume_ready=false AND wait_timeout_at <= now()`
- Writes synthetic timeout payload + sets `resume_ready=true`

**Failure handling:**
- On resume failure: release claim, increment `resume_attempt_count`
- After 3 failed attempts: mark execution `failed` with `ResumeExhausted`

---

## 7. Engine changes

### 7.1 Suspend path

`StepOperation.apply()` detects `result.isSuspended()` and sets state keys:
- `step.<n>.suspended = true`
- `step.any.state.suspended = true`

Rule `STEP_SUSPENDED_END_WORKFLOW` transitions the workflow to end state when `step.any.state.suspended == true`. The processor loop exits naturally via `isWorkflowEndState()`.

### 7.2 Resume path

`EngineWorkflowExecutor.executeWorkflowResume()` uses a two-phase approach:
- **Phase A:** Direct invocation of the suspended step via `executeWFItem` with the resume payload on the context. The step plugin sees `getResumePayload() != null`.
- **Phase B:** Remaining steps executed via `executeWorkflowImpl` with a fresh context (no resume payload). This prevents payload leaking to non-suspended steps.

### 7.3 Listener suppression

`BaseWorkflowExecutor.executeWorkflow()` suppresses `finishWorkflowExecution` listener callback when `result.isSuspended()` so the workflow is not marked as terminated.

---

## 8. Log writer

`FSStreamingLogWriter` implements `CheckpointableStreamingLogWriter`:
- `suspend()`: flush + fsync + close WITHOUT writing `^END^` footer
- Resume-mode constructor: `new FSStreamingLogWriter(stream, meta, format, true)` skips `outputBegin()` header

`LogFileStorageService.getLogFileWriterForResume(execution, meta)` opens the log file in append mode (`FileOutputStream(file, true)`) with `resumeMode=true`.

Result: one header at original start, events from both pre-suspend and post-resume, one footer at final completion.

---

## 9. UI

### 9.1 Execution detail page (show.gsp)

- **Confirmation panel:** Yellow "Waiting for Confirmation" panel with message, comment field, Approve (green) and Deny (red) buttons.
- **Dynamic appearance:** Panel is hidden initially. A JavaScript poller checks execution status every 2 seconds. When status becomes `waiting`, the panel slides in via jQuery `slideDown()`.
- **CSRF authentication:** CSRF token read directly from embedded `g:jsonToken` element and injected as `X-RUNDECK-TOKEN-KEY`/`X-RUNDECK-TOKEN-URI` headers on AJAX calls.
- **Feedback:** "Approved! Resuming..." or "Denied!" with auto-page-reload after 3 seconds.

### 9.2 Activity list (activityList.vue)

- `waiting` status recognized in `executionState()` method
- Orange pause icon (`fas fa-pause-circle text-warning`) for waiting executions
- Row class `nowwaiting` (not `nowrunning`) so it doesn't show the running animation
- Bulk-delete checkbox disabled for waiting executions

### 9.3 Step state display

`WorkflowExecutionStateListenerAdapter` recognizes `isSuspended()`:
- Step state: `WAITING` (not `FAILED`)
- Message: "Waiting for confirmation" (not "step suspended: awaiting confirmation")
- No failure metadata for suspended steps

---

## 10. Cluster semantics

### 10.1 Unowned-on-suspend

When an execution suspends, `serverNodeUUID` is cleared to NULL. Any node in the cluster can claim it. The DB is the sole arbiter — no cluster messaging required.

### 10.2 Shared filesystem

The log file at `outputfilepath` on the shared FS is closed (without footer) by the suspending node and reopened (in append mode) by the resuming node. Single-writer invariant enforced by the atomic claim.

### 10.3 Auth context freeze

The checkpoint persists `user` + `roles` from the original execution. On resume, `BaseAuthContextProvider.getAuthContextForUserAndRoles(user, roles)` reconstructs a synthetic auth context. Roles are frozen at suspend time — changes to the user's roles between suspend and resume don't affect the resumed execution.

---

## 11. Live-testing fixes applied

Issues found and fixed during live testing on a running Rundeck instance:

| # | Issue | Fix |
|---|---|---|
| 1 | Migration column name `server_node_uuid` vs `server_nodeuuid` | Use `server_nodeuuid` (Grails convention) |
| 2 | `@Scheduled` not firing | Add `@EnableScheduling` to Application.groovy |
| 3 | Hibernate lazy proxy on log writer open | Wrap in `withNewSession` |
| 4 | `frameworkService.storageTree` doesn't exist | Use `storageService.storageTreeWithContext(auth)` |
| 5 | `frameworkService.rundeckNodeService` doesn't exist | Inject `rundeckNodeService` as separate bean |
| 6 | `ExecutionListener` null in step dispatch | Use `WorkflowExecutionListenerImpl` (real class) |
| 7 | Empty node set + missing `job` data context | Add local node + job context map |
| 8 | Stale Hibernate object after raw SQL claim | Reload execution + `refresh()` in single session |
| 9 | Nested transaction version conflicts | Direct terminal write in same session |
| 10 | `ExecutionLogger` Groovy coercion broken | Proper anonymous class with all 3 methods |
| 11 | Browser 404 on approve (no CSRF token) | Direct token injection from `g:jsonToken` DOM element |
| 12 | `moduleResolution: "node"` can't resolve `@primeuix/themes/lara` | Changed to `"bundler"` in all 3 tsconfigs |
| 13 | PD-internal npm registry URLs in lockfiles | Rewrite to public registry URLs via sed |

---

## 12. Known limitations / follow-ups

### 12.1 Stale workflow state after resume

After resume completes, the per-node and per-step state indicators in the execution detail page show stale data (node spinner "running", post-confirm steps show "Waiting"). The resume path uses a simplified listener (`WorkflowExecutionListenerImpl` with a stub logger) that doesn't feed into the persisted workflow state model. Fix: wire the resume path through the real `WorkflowExecutionStateListenerAdapter` or update state after resume completes.

### 12.2 Confirm panel requires page load on `waiting`

The panel slides in dynamically via a 2-second poller, but only on the execution detail page. If the user is on a different page when the execution suspends, they must navigate to the execution to see the panel. Discovery is notification-driven (the `execution.waiting` notification event fires) but no in-app notification UI exists yet.

### 12.3 No requiredConfirmerRoles enforcement

The `requiredConfirmerRoles` config property is stored in the suspend metadata but not enforced by the `ApiConfirmController`. Any user with the `confirm` ACL action can approve regardless of roles.

### 12.4 Sub-workflow suspension forbidden

Suspend inside a child workflow (Job Reference step) is rejected by `SuspensionPolicy`. Cascade propagation is not implemented.

### 12.5 Parallel strategy suspension forbidden

Workflows using `ParallelWorkflowStrategy` cannot suspend. Multi-step quiesce is not implemented.

### 12.6 Operator pause UI not implemented

The pause/resume API endpoints work but there are no Pause/Resume buttons in the execution detail page. Operator pause is API-only.

### 12.7 Execution log output for resumed steps

The log output from resumed steps (Phase B) is captured by the stub logger and written to the server log, but may not appear in the execution's log file correctly because the log writer chain is simplified on resume.

### 12.8 PagerDuty npm registry dependency

Building the UI requires either `CLOUDSMITH_NPM_TOKEN` for the PD internal registry, or the workaround: rename `.npmrc` files, rewrite lockfile registry URLs via `sed`, and change `moduleResolution` to `"bundler"` in tsconfig files.

---

## 13. File inventory

### 13.1 New files

**Core Java (suspend package):**
- `SuspendRequest.java` — immutable value type
- `ResumePayload.java` — polymorphic interface with `@JsonTypeInfo`
- `ConfirmationPayload.java` — confirmation subtype
- `OperatorResumePayload.java` — operator-pause subtype
- `SuspendedStepResult.java` — engine-internal result marker
- `SuspendedFailureReason.java` — failure reason enum
- `CheckpointableContextComponent.java` — SPI for durable components
- `CheckpointableStreamingLogWriter.java` — SPI for log writer suspend
- `CheckpointableStreamingLogWriterFactory.java` — resume-mode factory
- `SuspensionPolicy.java` — validation (parallel, sub-workflow, components, writer)
- `SuspensionNotAllowedException.java`, `SuspensionFailedException.java`
- `ExecutionCheckpoint.java` — checkpoint JSON blob type
- `JacksonSubtypeRegistrar.java` — plugin subtype registration SPI
- `PreNextStepHook.java`, `HookResult.java` — between-step hook SPI

**Confirm plugin:**
- `plugins/confirm-plugin/build.gradle`
- `plugins/confirm-plugin/src/main/java/.../ConfirmWorkflowStep.java`

**Grails:**
- `ExecutionResumeService.groovy` — polling worker + resume logic
- `ApiConfirmController.groovy` — confirm API endpoints
- `ApiOperatorPauseController.groovy` — pause API endpoints
- `ExecutionConfirmation.groovy` — audit trail domain

**Migrations:**
- `SuspendResume-6.0.groovy` — 9 columns + 2 indexes on execution table
- `ExecutionConfirmation-6.0.groovy` — new table + index

**Tests:**
- `SuspendRequestTest.java` (5 tests)
- `SuspendedStepResultTest.java` (7 tests)
- `ResumePayloadTest.java` (1 test)
- `SuspensionPolicyTest.java` (10 tests)
- `ExecutionCheckpointTest.java` (4 tests)
- `ConfirmationPayloadTest.java` (2 tests)
- `WorkflowExecutionResultSuspendTest.java` (6 tests)
- `EngineWorkflowExecutorSpec.groovy` (3 Spock tests added)
- `FSStreamingLogWriterSuspendSpec.groovy` (4 Spock tests)

**Docs:**
- `docs/audits/workflow-suspend-resume.md`
- `docs/specs/workflow-suspend-resume.md`
- `docs/specs/confirm-workflow-step.md`
- `docs/specs/operator-pause.md`
- `docs/specs/test-plan-workflow-suspend-resume.md`
- `docs/walkthroughs/workflow-suspend-resume.md`
- `docs/cycles/workflow-suspend-resume.md`
- `docs/confirm-pause-resume-spec.md` (this file)

### 13.2 Modified files

- `StepExecutionResult.java` — `isSuspended()`, `getSuspendRequest()` defaults
- `StepExecutionContext.java` — `getResumePayload()`, `getSuspendMetadata()`, `suspend()` defaults
- `WorkflowExecutionResult.java` — `isSuspended()`, `getSuspendRequests()` defaults
- `ExecutionContextImpl.java` — resume payload/metadata fields, `pendingSuspension`, `pauseCheckSupplier`
- `EngineWorkflowExecutor.java` — suspend rule, constants, `executeWorkflowResume()`
- `StepOperation.java` — suspended state detection
- `StepCallable.java` — operator-pause check
- `StepPluginAdapter.java` — `pendingSuspension` detection
- `BaseWorkflowExecutor.java` — `BaseWorkflowExecutionResult` suspend fields, listener suppression
- `WorkflowExecutionServiceThread.java` — `isSuspended()` accessor
- `WorkflowExecutionStateListenerAdapter.java` — WAITING state for suspended steps
- `AuthConstants.java` — `ACTION_CONFIRM`, `ACTION_PAUSE`
- `Execution.groovy` — 9 new fields, criteria exclusion, state mapping
- `ExecutionService.groovy` — `EXECUTION_WAITING`, `onWorkflowSuspended()`, `pauseCheckSupplier` wiring, notification event
- `ExecutionJob.groovy` — suspended branch, skip terminal write
- `ExecutionUtilService.groovy` — `suspendExecution()` log writer suspend
- `FSStreamingLogWriter.groovy` — `CheckpointableStreamingLogWriter`, `suspend()`, `resumeMode`
- `LogFileStorageService.groovy` — `getLogFileWriterForResume()`
- `Application.groovy` — `@EnableScheduling`
- `UrlMappings.groovy` — confirm + pause API routes
- `show.gsp` — confirmation panel UI
- `activityList.vue` — waiting status icon + state
- `settings.gradle` — `confirm-plugin` module
- `build.gradle` — `confirm-plugin` in bundled plugins
- `changelog.groovy` — migration includes
- `tsconfig.json`, `tsconfig.build.json`, `tsconfig.app.json` — `moduleResolution: "bundler"`
