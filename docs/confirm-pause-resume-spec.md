# Confirm / Pause / Resume — As-Built Specification

**Status:** Implemented and live-tested on branch `cycle/workflow-suspend-resume`. E2E verified via API.
**Date:** 2026-04-18 (updated)
**Branch:** ~70 files, ~9000+ lines.

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

## 2. End-to-end flow (verified via automated API test)

```
alice runs job: echo "Step 1 done" → confirm → echo "Step 3 done"
  ↓
Step 1 runs normally on localhost → SUCCEEDED
  ↓
Step 2 (confirm) calls context.suspend(request)
  → SuspendedStepResult stored in pendingSuspension field
  → StepPluginAdapter detects it, returns to engine
  → Engine rule STEP_SUSPENDED_END_WORKFLOW fires → loop exits
  → ExecutionJob detects thread.isSuspended()
  → Log writer suspend-closed (no ^END^ footer) via recursive MultiLogWriter traversal
  → DB: status='waiting', serverNodeUUID=NULL, checkpoint saved with completedStepResults
  → Thread released
  ↓
API: GET /execution/{id} → status: "waiting"
API: GET /execution/{id}/state →
  Step 1: SUCCEEDED (localhost: SUCCEEDED)
  Step 2: RUNNING (localhost: RUNNING)
  Step 3: WAITING (localhost: WAITING)
API: GET /execution/{id}/confirm/status → waiting: true, message: "...", callerCanConfirm: true
  ↓
bob calls POST /execution/{id}/confirm with {"decision":"approve","comment":"LGTM"}
  → ExecutionConfirmation audit row written
  → resume_ready=true, resume_payload=ConfirmationPayload JSON
  → Response: {"resumeReady":true,"confirmationId":1,"decision":"approve"}
  ↓
ExecutionResumeService polling worker (2s tick)
  → Finds waiting + resumeReady row
  → Atomic claim: UPDATE SET serverNodeUUID=me, status='running'
  → Rehydrates context in single Hibernate session
  → Reopens log writer in append mode (no second header)
  → Creates state listener via WorkflowService.createWorkflowStateListenerForExecution
  → Enters EngineWorkflowExecutor.executeWorkflowResume:
    beginWorkflowExecution → initializes state model
    Replay: replayCompletedStep(1, true, "localhost") → Step 1 shown as SUCCEEDED
    Phase A: beginWorkflowItem(2) → executeWFItem(confirm) → finishWorkflowItem(2)
      → Plugin reads ConfirmationPayload, logs "[confirm] Confirmed by ..."
      → Returns success → Step 2 shown as SUCCEEDED
    Phase B: beginWorkflowItem(3) → executeWFItem(echo) → finishWorkflowItem(3)
      → Step 3 dispatched to localhost → "Step 3 done"
      → Step 3 shown as SUCCEEDED
    finishWorkflowExecution → overall SUCCEEDED
  → Terminal write: status='succeeded', dateCompleted set
  → Suspend columns cleared (I11)
  ↓
API: GET /execution/{id} → status: "succeeded"
API: GET /execution/{id}/state →
  Overall: SUCCEEDED
  Step 1: SUCCEEDED (localhost: SUCCEEDED)
  Step 2: SUCCEEDED (localhost: SUCCEEDED)
  Step 3: SUCCEEDED (localhost: SUCCEEDED)
API: GET /execution/{id}/output → all log entries from both phases visible
API: GET /execution/{id}/confirmations → [{confirmedBy:"...",decision:"approve",comment:"LGTM"}]
```

**Deny path (also verified):** Step 1 SUCCEEDED → Step 2 FAILED (denied) → Step 3 NOT_STARTED → Overall FAILED.

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
2. `execution.refresh()` to sync version after raw SQL claim
3. Parse checkpoint JSON → `ExecutionCheckpoint` (includes `completedStepResults`)
4. Parse resume payload → `ResumePayload` (polymorphic Jackson)
5. Rebuild auth context from frozen user + roles via `getAuthContextForUserAndRoles`
6. Reopen log writer in append mode via `LogFileStorageService.getLogFileWriterForResume`
7. Build log writer chain: `LoglevelThresholdLogWriter` → `ContextLogWriter` → `LoggerWithContext` (see §8.3)
8. Create `ContextManager` for step/node context tracking
9. Create `WorkflowExecutionListenerImpl` with the context-aware logger
10. Create state listener via `WorkflowService.createWorkflowStateListenerForExecution`
11. Combine `[contextmanager, baseListener, execStateListener, logOutFlusher, logErrFlusher]` into `MultiWorkflowExecutionListener`
12. Build `ExecutionContextImpl` with resume payload + suspend metadata
13. Install thread-bound stdout/stderr streams via `sysThreadBoundOut`/`sysThreadBoundErr`
14. Call `EngineWorkflowExecutor.executeWorkflowResume` with 7-arg overload passing `execStateListener` (for state replay) and `localNodeName`
15. In finally: tear down thread-bound streams, close log writer
16. On completion: write terminal state directly in the same Hibernate session (avoids nested transaction version conflicts)

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

**Important:** The engine's rule-based `WorkflowEngineOperationsProcessor` does NOT guarantee result ordering in the result set. The suspended step may appear before completed steps. `onWorkflowSuspended` handles this by counting non-suspended results to derive the `suspendedStepIndex` and collecting `completedStepResults` by filtering on `!isSuspended()` rather than by position.

### 7.2 Resume path

`EngineWorkflowExecutor.executeWorkflowResume()` uses a two-phase approach with full lifecycle events:

1. **`beginWorkflowExecution`** — fires on the listener to initialize the state model
2. **Completed step replay** — for each step recorded in the checkpoint's `completedStepResults`, calls `WorkflowExecutionStateListenerAdapter.replayCompletedStep(step, success, nodeName)` to transition step states through `RUNNING → SUCCEEDED/FAILED` with proper node-level events. This is done via the state adapter directly (not the multi-listener) because `finishWorkflowItem` skips state notifications for node-dispatch steps.
3. **Phase A:** Direct invocation of the suspended step via `executeWFItem` with the resume payload on the context. The step plugin sees `getResumePayload() != null`. Listener events (`beginWorkflowItem`/`finishWorkflowItem`) are fired around the call.
4. **Phase B:** Remaining steps executed via direct `executeWFItem` calls in a loop (NOT via `executeWorkflowImpl`). Each step gets proper `beginWorkflowItem`/`finishWorkflowItem` listener events. Direct execution avoids the rule engine's cross-step condition resolution issue, where a sub-workflow can't resolve `after.step.N` conditions referencing steps from the original workflow.
5. **`finishWorkflowExecution`** — fires in a `finally` block (suppressed for re-suspended results) to transition the state model to `SUCCEEDED`/`FAILED`.

The two-arg overload `executeWorkflowResume(ctx, item, checkpoint, payload, metadata)` delegates to the seven-arg overload which also accepts a `WorkflowExecutionStateListenerAdapter` and `localNodeName` for state replay.

### 7.3 Listener suppression

`BaseWorkflowExecutor.executeWorkflow()` suppresses `finishWorkflowExecution` listener callback when `result.isSuspended()` so the workflow is not marked as terminated. Similarly, `executeWorkflowResume()` suppresses `finishWorkflowExecution` when the result is re-suspended.

### 7.4 State replay for completed steps

`WorkflowExecutionStateListenerAdapter.replayCompletedStep(step, success, nodeName)` is a public method added for resume state replay. Unlike the normal `beginWorkflowItem`/`finishWorkflowItem` path, it fires state transitions directly regardless of whether the step is a node-dispatch step, including per-node `RUNNING → SUCCEEDED` transitions when `nodeName` is provided.

---

## 8. Log writer

### 8.1 Suspend-close

`FSStreamingLogWriter` implements `CheckpointableStreamingLogWriter`:
- `suspend()`: flush + fsync + close WITHOUT writing `^END^` footer
- Resume-mode constructor: `new FSStreamingLogWriter(stream, meta, format, true)` skips `outputBegin()` header

`ExecutionUtilService.suspendExecution()` walks the log writer chain recursively via `findAndSuspendCheckpointableWriter()` to find the `FSStreamingLogWriter`. The chain traversal handles both `FilterStreamingLogWriter` delegates and `MultiLogWriter` fan-outs (the standard log writer chain is `ExecutionLogWriter → LoglevelThresholdLogWriter → MultiLogWriter → DisablingLogWriter → FSStreamingLogWriter`).

### 8.2 Resume-open

`LogFileStorageService.getLogFileWriterForResume(execution, meta)` opens the log file in append mode (`FileOutputStream(file, true)`) with `resumeMode=true`.

### 8.3 Resume log writer chain

The resume path mirrors the normal execution path's log writer chain:

1. **`LoglevelThresholdLogWriter`** wraps the `ExecutionLogWriter` to filter debug/verbose framework messages (e.g., `[workflow] Begin step:`) from the execution log. Uses the job's configured log level (default INFO).
2. **`ContextLogWriter`** wraps the filtered writer to stamp each log event with the current step/node context metadata (`stepctx`, `node`).
3. **`LoggerWithContext`** combines the `ContextLogWriter` with a `ContextManager` into an `ExecutionLogger` used by `WorkflowExecutionListenerImpl`.
4. **`ContextManager`** is included in the `MultiWorkflowExecutionListener` listener list so it receives step begin/end events and tracks the active step context.
5. **Thread-bound stdout/stderr** — `sysThreadBoundOut`/`sysThreadBoundErr` are installed via `loggingService.createLogOutputStream()` with the filtered writer and context manager, so command stdout (e.g., `echo "Step 3 done"`) is captured in the execution log with proper step context.
6. **`LogFlusher`** listeners are included in the multi-listener to flush stdout/stderr output after node steps.

Result: one header at original start, events from both pre-suspend and post-resume with correct step context metadata, debug messages filtered, one footer at final completion. The nodes view shows only relevant output per step.

### 8.4 Explicit suspend/resume trigger markers

The execution log carries explicit workflow-level trigger lines so operators can see pause/resume transitions directly in the output view (without having to infer them from the rule engine's "Step N did not run" diagnostics).

- **Suspend marker** — `ExecutionUtilService.suspendExecution()` calls `loghandler.log(...)` with a line of the form
  `[suspend] Execution paused — reason: "<reason>", waiting for: <waitingFor>, timeout: <duration>`
  before the writer is torn down. Fields come from the first `SuspendRequest` on the thread's result (`reason`, `waitingFor`, `timeoutMs`). `timeoutMs` is rendered as `1d2h30m` style via `formatDurationMs()`.
- **Resume marker** — `ExecutionResumeService.resumeExecution()` calls `loghandler.log(...)` immediately after the log writer reopens, with a payload-aware message:
  - `ConfirmationPayload` approve → `[resume] Execution resumed — approved by <user>: <comment>`
  - `ConfirmationPayload` deny → `[resume] Execution resumed — denied by <user>: <comment>`
  - `ConfirmationPayload` timeout → `[resume] Execution resumed — confirmation timed out`
  - `OperatorResumePayload` → `[resume] Execution resumed — operator resume by <user>[: <comment>]`
  - Unknown subtype → `[resume] Execution resumed — payload: <type>`

Both markers go through `ExecutionLogWriter.log(...)` at NORMAL level, so they survive the `LoglevelThresholdLogWriter` filter, land without `stepctx` metadata (workflow-level, not attached to any step), and appear inline in the output view between pre-suspend and post-resume content.

Note: the rule engine's "Step N did not run" warnings still print above the `[suspend]` line because they're emitted during workflow tear-down, before `suspendExecution()` runs. They are left in place as additional diagnostics.

---

## 9. UI

### 9.1 Execution detail page (UIPlugin)

The confirmation UI is rendered entirely by the `ConfirmUIPlugin` — a `UIPlugin` that injects JS/CSS at the `execution/show` path. No modifications to show.gsp are required.

- **`ConfirmUIPlugin.java`** — applies at `execution/show`, loads `confirm-execution.js` and `confirm-execution.css`
- **`confirm-execution.js`** — polls `/api/{v}/execution/{id}/confirm/status`, renders a card panel with message, comment field, Approve/Deny buttons when the execution is waiting
- **Styling:** Uses Rundeck's native `card`, `btn btn-success`, `btn btn-danger`, `form-control` CSS classes to inherit the page theme
- **CSRF authentication:** Reads the existing `exec_cancel_token` `g:jsonToken` element on the page and injects `X-RUNDECK-TOKEN-KEY`/`X-RUNDECK-TOKEN-URI` headers via jQuery `beforeSend`
- **jQuery:** Uses jQuery (already loaded by Rundeck) for AJAX calls, matching Rundeck's existing patterns
- **Feedback:** "Approved! Resuming..." or "Denied!" with auto-page-reload after 3 seconds

This architecture means each HIL primitive (choose, ask, review, attest, rank) can ship as a self-contained plugin JAR with its own UIPlugin + JS/CSS rendering — no upstream GSP changes needed.

### 9.2 Activity list (activityList.vue)

- `waiting` status recognized in `executionState()` method
- Orange pause icon (`fas fa-pause-circle text-warning`) for waiting executions
- Static orange progress bar with "Waiting for confirmation" label (not the animated barber pole)
- Row class `nowwaiting` (not `nowrunning`) so it doesn't show the running animation
- Bulk-delete checkbox disabled for waiting executions

### 9.3 Step state display

`WorkflowExecutionStateListenerAdapter` recognizes `isSuspended()`:
- Step state: `WAITING` (not `FAILED`)
- Message: "Waiting for confirmation" (not "step suspended: awaiting confirmation")
- No failure metadata for suspended steps

After resume completes, all step states reflect their actual outcome:
- Steps before suspend: `SUCCEEDED` (replayed via `replayCompletedStep`)
- Suspended step: `SUCCEEDED` or `FAILED` (from Phase A result)
- Steps after suspend: `SUCCEEDED` or `FAILED` (from Phase B execution)
- Overall: `SUCCEEDED` or `FAILED` (from `finishWorkflowExecution`)

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
| 14 | Step 2 state `FAILED` instead of `WAITING` during suspend | `WorkflowExecutionStateListenerAdapter` checks `isSuspended()` first |
| 15 | Step states stale after resume (Steps 1/2 show `WAITING`/`NOT_STARTED`) | `replayCompletedStep()` fires synthetic state transitions including node-level events |
| 16 | Step 3 not executing during resume (rule engine condition unresolvable) | Replace Phase B `executeWorkflowImpl` with direct `executeWFItem` loop |
| 17 | Resume log output missing from execution log | Use `ExecutionLogger` wrapping `loghandler` instead of server-log stub |
| 18 | Log writer suspend can't find `FSStreamingLogWriter` through `MultiLogWriter` | Recursive traversal handling `MultiLogWriter` fan-outs |
| 19 | Premature `^END^` footer written on suspend | Fixed by #18 — `CheckpointableStreamingLogWriter.suspend()` now reached correctly |
| 20 | Overall execution state `RUNNING` after resume completion | Fire `finishWorkflowExecution` in `executeWorkflowResume` finally block |
| 21 | `completedStepResults` empty in checkpoint | Populate from `result.getResultSet()` in `onWorkflowSuspended` |
| 22 | `WorkflowExecutionListenerImpl` constructor mismatch on resume | Use `ExecutionLogger` anonymous class, not `ExecutionLogWriter` directly |
| 27 | Resumed executions missing from activity/history page | Call `logExecution()` in resume path to write execution report |
| 28 | Confirm log message shows empty `(roles: [])` | Remove roles from log format string in `ConfirmWorkflowStep` |
| 29 | Confirm output variables not available in subsequent steps | Plugin writes to both outputContext and sharedDataContext; engine merges shared context back after each phase |
| 30 | Confirmation UI hardcoded in show.gsp (not upstream-safe) | Refactored to UIPlugin: `ConfirmUIPlugin` + `confirm-execution.js` + CSS, no GSP changes needed |
| 31 | UIPlugin panel not appearing dynamically on waiting | Poll execution status first, then fetch confirm/status; add `X-Rundeck-Ajax` header to GET calls |
| 32 | UIPlugin approve button returning "Not found" | Switch from fetch API to jQuery; read CSRF token from existing `exec_cancel_token` element |
| 33 | Approved executions show splat icon in activity list | Use `EXECUTION_SUCCEEDED`/`EXECUTION_FAILED` constants instead of `'true'`/`'false'` in resume terminal write |
| 23 | `completedStepResults` flaky (0 or 1) due to non-deterministic result set ordering | Don't assume suspended step is last in result set; count completed results instead |
| 24 | Resumed log entries lack `stepctx` metadata (nodes view can't attribute to steps) | Wire `ContextManager` + `ContextLogWriter` + `LoggerWithContext` in resume path |
| 25 | Command stdout not captured in resume path | Install thread-bound stdout/stderr via `sysThreadBoundOut`/`sysThreadBoundErr` |
| 26 | Verbose `[workflow]` framework messages in nodes view | Add `LoglevelThresholdLogWriter` to filter debug/verbose from execution log |

---

## 12. Known limitations / follow-ups

### 12.1 ~~Stale workflow state after resume~~ — RESOLVED

~~After resume completes, the per-node and per-step state indicators show stale data.~~ Fixed by:
- Firing `beginWorkflowExecution` / `finishWorkflowExecution` lifecycle events in `executeWorkflowResume`
- Replaying completed steps via `WorkflowExecutionStateListenerAdapter.replayCompletedStep()` with node-level events
- Using direct `executeWFItem` calls in Phase B instead of `executeWorkflowImpl` (avoids rule engine cross-step condition issues)
- Wiring the resume path through the real `WorkflowExecutionStateListenerAdapter` via `WorkflowService.createWorkflowStateListenerForExecution`

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

### 12.7 ~~Execution log output for resumed steps~~ — RESOLVED

~~Log output from resumed steps was only in the server log.~~ Fixed by:
- Building a full log writer chain: `LoglevelThresholdLogWriter` → `ContextLogWriter` → `LoggerWithContext` with `ContextManager` for step context stamping
- Installing thread-bound stdout/stderr streams so command output is captured with correct `stepctx` metadata
- Fixing `ExecutionUtilService.suspendExecution()` to recursively traverse `MultiLogWriter` fan-outs when finding the `CheckpointableStreamingLogWriter`, preventing premature `^END^` footer
- Filtering debug/verbose framework messages (e.g., `[workflow] Begin step:`) via `LoglevelThresholdLogWriter` so only relevant output appears in the nodes view
- The resume path now appends cleanly: one `^text/x-rundeck-log-v2.0^` header, events from both pre-suspend and post-resume with correct step context, debug messages filtered, one `^END^` footer

### 12.8 PagerDuty npm registry dependency

Building the UI requires either `CLOUDSMITH_NPM_TOKEN` for the PD internal registry, or the workaround: rename `.npmrc` files, rewrite lockfile registry URLs via `sed`, and change `moduleResolution` to `"bundler"` in tsconfig files.

### 12.9 ~~Confirm step does not export output variables~~ — RESOLVED

The confirm plugin now exports confirmation data as output variables on the resume path. Subsequent steps reference them as:

| Variable | Description |
|---|---|
| `${confirm.decision}` | `approve` or `deny` |
| `${confirm.comment}` | Comment entered by the confirmer |
| `${confirm.confirmedBy}` | Username of the confirmer |
| `${confirm.confirmedAt}` | Timestamp of confirmation |

Example Step 3: `echo "Approved by: ${confirm.confirmedBy}, comment: ${confirm.comment}"`

Implementation: the plugin writes to both `pluginContext.getOutputContext().addOutput(ContextView.global(), ...)` and directly to `context.getSharedDataContext().merge(ContextView.global(), ...)`. The engine's `executeWorkflowResume` merges the step's shared context back into the parent after each phase so subsequent steps see the data.

### 12.10 Confirmer identity shows "unknown"

The `confirmedBy` field in audit records and log output shows "unknown" when using API token authentication. The `ApiConfirmController` doesn't extract the authenticated username from the API token auth context. Browser session auth is not currently used for the confirm API calls.

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
- `ExecutionResumeService.groovy` — polling worker + resume logic; `buildResumeMarker()` emits explicit `[resume]` trigger line (§8.4)
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
- `WorkflowExecutionStateListenerAdapter.java` — WAITING state for suspended steps, `replayCompletedStep()` for resume state replay
- `AuthConstants.java` — `ACTION_CONFIRM`, `ACTION_PAUSE`
- `Execution.groovy` — 9 new fields, criteria exclusion, state mapping
- `ExecutionService.groovy` — `EXECUTION_WAITING`, `onWorkflowSuspended()`, `pauseCheckSupplier` wiring, notification event
- `ExecutionJob.groovy` — suspended branch, skip terminal write
- `ExecutionUtilService.groovy` — `suspendExecution()` with recursive `MultiLogWriter` traversal, `buildSuspendMarker()` for explicit `[suspend]` trigger line (§8.4)
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
