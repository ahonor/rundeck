# Operator Pause/Resume Specification

**Version:** 1.0
**Date:** 2026-04-18
**Status:** Specified. Backend wired, UI not yet implemented.

---

## 1. Purpose

Allow an operator to pause a running execution at the next step boundary and resume it later. This is an ad-hoc operational control — distinct from the confirm step, which is authored into the job definition.

---

## 2. Definitions

| Term | Meaning |
|---|---|
| **Pause** | A request to stop the execution before the next step begins. The current step runs to completion. |
| **Resume** | A request to continue the execution from the paused step. |
| **Step boundary** | The point between two sequential workflow steps where the engine checks for pause. |
| **Operator** | A user with the `pause` ACL action on the execution resource. |

---

## 3. Invariants

| ID | Invariant |
|---|---|
| P1 | Pause is cooperative. A running step always completes before the pause takes effect. |
| P2 | No thread is held while paused. The execution releases its JVM thread and parks in the database. |
| P3 | A paused execution is resumable by any node in the cluster. The DB is the sole arbiter. |
| P4 | Pause does not alter the step's execution outcome. The step that was running when pause was requested completes with its natural result (success or failure). |
| P5 | Resume runs the paused step fresh. The step was never invoked; it executes as a first invocation, not as a resume. |
| P6 | A paused execution has `status='waiting'` and `metadata.type='operator-pause'`. It is visually and programmatically distinguishable from a confirm-waiting execution. |
| P7 | The `resumePayload` is cleared before invoking the paused step. This prevents a confirm step from misinterpreting an `OperatorResumePayload` as a `ConfirmationPayload`. |

---

## 4. Authorization

| Action | Resource | Grants |
|---|---|---|
| `pause` | `execution` | Pause a running execution; resume an operator-paused execution |

A single ACL action governs both pause and resume. An operator who can pause can also resume.

The `pause` action is independent of the `confirm` action. A user may have one, both, or neither.

---

## 5. API

### 5.1 Pause

```
POST /api/{v}/execution/{id}/pause
Content-Type: application/json
```

**Request body** (optional):
```json
{"reason": "maintenance window starting"}
```

**Success response** `200`:
```json
{
  "paused": true,
  "effectiveAfter": "next step boundary"
}
```

**Error responses:**

| Code | Condition |
|---|---|
| 400 | Missing execution ID |
| 403 | Caller lacks `pause` ACL action |
| 404 | Execution not found |
| 409 | Execution is not running (already waiting, completed, or aborted) |

**Effect:** Sets `pause_requested=true` on the execution row. The engine reads this flag before each step invocation.

### 5.2 Resume

```
POST /api/{v}/execution/{id}/resume
Content-Type: application/json
```

**Request body** (optional):
```json
{"comment": "maintenance complete, resuming"}
```

**Success response** `200`:
```json
{
  "resumed": true,
  "resumedBy": "admin"
}
```

**Error responses:**

| Code | Condition |
|---|---|
| 400 | Missing execution ID |
| 403 | Caller lacks `pause` ACL action |
| 404 | Execution not found |
| 409 | Execution is not waiting, or waiting for a different event type (e.g., confirmation) |

**Effect:** Clears `pause_requested`, writes an `OperatorResumePayload`, sets `resume_ready=true`. The resume worker picks it up on its next poll cycle (≤2 seconds).

### 5.3 Status

```
GET /api/{v}/execution/{id}/pause/status
```

**Response** `200`:
```json
{
  "pauseRequested": true,
  "currentStatus": "waiting",
  "suspendType": "operator-pause",
  "requestedBy": "admin",
  "requestedAt": "2026-04-18T19:06:45Z",
  "reason": "maintenance window starting",
  "waitStartedAt": "2026-04-18T19:06:46Z",
  "callerCanPause": true
}
```

| Field | Type | Description |
|---|---|---|
| `pauseRequested` | boolean | True if a pause has been requested but not yet taken effect |
| `currentStatus` | string | Current execution status (`running`, `waiting`, `succeeded`, etc.) |
| `suspendType` | string | `operator-pause` if paused by operator; `confirmation` if waiting for confirm step; null otherwise |
| `requestedBy` | string | User who requested the pause |
| `reason` | string | Reason provided by the operator |
| `waitStartedAt` | string | ISO timestamp when the execution entered waiting state |
| `callerCanPause` | boolean | Whether the current user has the `pause` ACL action |

---

## 6. UX

### 6.0 Packaging

Operator pause/resume is a **core operational control** (like Kill/Abort), not a step primitive. The Pause and Resume UI must be built directly into the core `execution/show` page:

- JS and CSS live under `rundeckapp/grails-app/assets/javascripts/` and `.../stylesheets/`
- Included in `rundeckapp/grails-app/views/execution/show.gsp` via `<asset:javascript>` / `<asset:stylesheet>`
- The UI is present on every execution detail page, with visibility controlled by the rules in §6.1 and §6.2

The Pause/Resume UI **must not** be packaged as a `UIPlugin`. UIPlugins are reserved for *step-primitive* UIs (e.g., `ConfirmUIPlugin` — renders only when a `confirm` step is waiting). Shipping pause as a plugin would create an inconsistent mental model where a core control disappears when a plugin JAR is missing.

### 6.1 Pause/Resume button — single modal control

Pause and Resume are rendered as a **single `<span class="btn">` element** in the execution action row, next to the Kill button. The button's label, CSS class, icon, click handler, and visibility are all driven by one Knockout computed (`pauseControlMode` in `executionStateKO.js`) that derives its state from fields served by `ajaxExecState` on every poll tick.

The button is never a separate "Pause" and "Resume" pair — it is a single control that **transitions in place**. See `rundeckapp/grails-app/views/execution/show.gsp` and `pauseControlMode` / `pauseControlLabel` / `pauseControlCss` / `pauseControlAction` in `executionStateKO.js` for the implementation.

#### 6.1.1 Modes

| Mode      | Trigger                                                                                     | Label                | Style             | Icon       | Click action         |
|-----------|---------------------------------------------------------------------------------------------|----------------------|-------------------|------------|----------------------|
| `pause`   | `executionState == RUNNING` AND `callerCanPause` AND `totalSteps > 1` AND `!pauseRequested` | `Pause`              | `btn btn-warning` | `fa-pause` | POST `/execution/pauseExecution` |
| `pending` | `executionState == RUNNING` AND `pauseRequested`                                            | `Pause requested…`   | `btn btn-warning disabled` (cursor: not-allowed) | `fa-pause` | none (button is disabled) |
| `resume`  | `executionState == WAITING` AND `suspendType == 'operator-pause'` AND `callerCanPause`      | `Resume`             | `btn btn-success` | `fa-play`  | POST `/execution/resumeExecution` |
| `hidden`  | All other states                                                                            | (not rendered)       | —                 | —          | —                    |

#### 6.1.2 No reason/comment prompt

The button fires the POST directly on click — no `prompt()` dialog for an optional reason. Rationale: Kill doesn't prompt for a reason either; a modal prompt interrupts the operator for a field the UI does not require. Operators who want to record audit text can still call the API directly with a `reason`/`comment` body; the API accepts both optionally.

#### 6.1.3 Browser endpoints, not `/api/`

The button POSTs to `/execution/pauseExecution` and `/execution/resumeExecution` — **not** to the `/api/{v}/execution/{id}/pause` URLs from §5. Both URL shapes route to the same controller actions via `UrlMappings.groovy`. The reason: Rundeck's `ApiVersionInterceptor` demands a URI-scoped CSRF token for every non-`GET` request to `/api/*`, and the token Rundeck emits on the execution/show page is scoped to the show page URL, not the API path. The Kill button solves this by POSTing to `/execution/cancelExecution`; Pause follows the same pattern so the `exec_cancel_token` + `_createAjaxSendTokensHandler` / `_createAjaxReceiveTokensHandler` flow just works.

The `/api/` endpoints remain the canonical interface for API-token callers; the `/execution/*` endpoints are the in-browser mirror.

### 6.2 Execution/show progress bar

When `executionState == WAITING`, the normal live progress bar is suppressed and replaced with a **static orange bar** whose label is:

| `suspendType`      | Label                       |
|--------------------|-----------------------------|
| `operator-pause`   | `Paused by operator`        |
| `confirmation`     | `Waiting for confirmation`  |

This matches the rendering used by the activity list (§6.3), so a paused execution looks visually the same wherever it appears.

### 6.3 Activity list

A paused execution displays:
- Orange pause icon (`fas fa-pause-circle text-warning`)
- Static progress bar (not the animated barber pole), label derived from `suspendType`:
  - `operator-pause` → "Paused by operator"
  - `confirmation` → "Waiting for confirmation"

### 6.4 Execution detail page — step state

The paused state is visible in the step state display:
- Steps before the pause: `SUCCEEDED` (or their actual outcome)
- Paused step: `WAITING` (has not started yet)
- Steps after the pause: `NOT_STARTED`

### 6.5 Data flow

All state driving the button and the progress bar comes from `ajaxExecState` (`controllers.ExecutionController.ajaxExecState`), which the `FlowState` poller on the show page calls every 1500 ms. The response carries:

| Field              | Type    | Notes                                                               |
|--------------------|---------|---------------------------------------------------------------------|
| `pauseRequested`   | boolean | `Execution.pauseRequested` column                                   |
| `suspendType`      | string  | `operator-pause`, `confirmation`, or `null`; parsed from `suspendMetadata` |
| `callerCanPause`   | boolean | Result of `authorizeProjectExecutionAll(..., [ACTION_PAUSE])`       |
| `totalSteps`       | integer | `workflowState.stepCount`; 0 until the state summary is available   |

These are mapped into KO observables by `followFlowState.updateState`. The UI must not poll separate endpoints for pause state — all of it rides on the existing follow-poll so a paused execution looks right the moment the user lands on the page.

---

## 7. Engine behavior

### 7.1 Pause detection

`StepCallable.apply()` checks `ExecutionContextImpl.isPauseRequested()` before invoking each step. The `pauseCheckSupplier` is a `Supplier<Boolean>` set by `ExecutionService.executeAsyncBegin` that reads `pause_requested` from the execution DB row via a fresh Hibernate session.

If `isPauseRequested()` returns true:
1. Synthesize a `SuspendedStepResult` with `metadata.type=operator-pause`
2. Return the result without invoking the step
3. The engine's `STEP_SUSPENDED_END_WORKFLOW` rule fires
4. The normal suspend path runs (log writer suspend-close, checkpoint save, status='waiting')

### 7.2 Resume execution

The resume worker (`ExecutionResumeService`) claims the execution and calls `executeWorkflowResume`. For operator-pause:

1. Detect `metadata.type=operator-pause` from the checkpoint
2. **Clear `resumePayload` to null** (invariant P7)
3. Phase A invokes the paused step as a fresh first invocation
4. Phase B runs remaining steps

Clearing the payload ensures the step runs as if it were never paused. This is critical when the paused step is a confirm step — it must see `resumePayload == null` to enter its own confirm-wait flow.

### 7.3 Checkpoint

The checkpoint records the paused step as `suspendedStepIndex`. Completed steps before the pause are recorded in `completedStepResults` and replayed on resume via `replayCompletedStep`.

---

## 8. Edge cases

| Case | Behavior |
|---|---|
| Single-step job | No Pause button. API accepts but no effect — step finishes, workflow ends. |
| Last step executing | No Pause button. API accepts but no effect. |
| Pause while confirm step is waiting | API returns `409` — execution is not running. |
| Resume a confirm-waiting execution via `/resume` | API returns `409` — suspend type is `confirmation`. Use `/confirm`. |
| Rapid pause then unpause | If cleared before engine reads the flag, pause may not take effect. Acceptable — operator's intent is "don't pause." |
| Pause lands before a confirm step | Operator resumes → step runs fresh → confirm plugin calls `context.suspend()` → enters confirm-wait flow. Correct. |
| Pause during node-dispatch fan-out | Pause takes effect after the entire dispatch completes, not between nodes. |
| Step failure before pause takes effect | The failing step's result is preserved. If `keepgoing=false`, the workflow fails normally without reaching the pause point. |

---

## 9. Success criteria

| # | Criterion | Verification |
|---|---|---|
| S1 | Pause API sets flag, engine parks at next step boundary | Run 3-step job, pause during step 1, verify step 1 completes and step 2 does not start |
| S2 | Resume continues from paused step | Resume the paused execution, verify steps 2 and 3 run |
| S3 | Step states are correct after resume | Step 1: SUCCEEDED, Step 2: SUCCEEDED, Step 3: SUCCEEDED |
| S4 | Log output spans both pre-pause and post-resume | Single log file, no duplicate headers, correct stepctx on all entries |
| S5 | Activity list shows correct icons | Paused: orange pause. Completed after resume: green check. |
| S6 | Pause button hidden for single-step jobs | Create 1-step job, run it, verify no Pause button |
| S7 | Pause button hidden at last step | Run 2-step job, verify Pause button disappears when step 2 starts |
| S8 | Resume of operator-paused-before-confirm works | Job: echo → confirm → echo. Pause before confirm. Resume. Confirm step enters its own wait flow. |
| S9 | ACL enforcement | User without `pause` action cannot see Pause button or call API |
| S10 | Cluster resume | Pause on node A, resume claimed by node B. Verify execution completes. |

---

## 10. Implementation status

| Component | Status |
|---|---|
| `pause_requested` DB field | Done |
| `pauseCheckSupplier` wiring | Done |
| `StepCallable` pause detection | Done |
| `OperatorResumePayload` | Done |
| `ApiOperatorPauseController` (3 endpoints) | Done |
| URL mappings | Done |
| ACL action `ACTION_PAUSE` | Done |
| Resume path: clear payload for operator-pause | **Not done** |
| UI: Pause/Resume buttons (core assets, not plugin — see §6.0) | **Not done** |
| Activity list: "Paused" label | **Not done** (uses "Waiting for confirmation") |
| Success criteria verification | **Not done** |
