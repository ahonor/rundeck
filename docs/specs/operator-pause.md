# spec/operator-pause

**Phase:** 2 (Spec) — companion to `docs/specs/workflow-suspend-resume.md`.
**Status:** Draft pending Phase 2 review.
**Owned surface:** Operator-initiated pause of a running workflow execution at the next step boundary, its persistence, API surface, ACL action, notification event, and minimum UI affordance.
**Depends on:** `docs/specs/workflow-suspend-resume.md` — this feature is the second first-class consumer of the suspend/resume primitive (alongside `docs/specs/confirm-workflow-step.md`).

This feature gives operators a generic "park this execution until I say so" button that works on any running execution, regardless of whether its workflow contains confirmation steps. Unlike plugin-initiated suspension (confirm step), operator-pause is triggered from outside the step plugin via the API or UI.

---

## 1. What the area owns

The operator-initiated pause/resume lifecycle: the `pause_requested` flag on the `Execution` row, the engine's between-steps hook that honors it, the `operator-pause` suspension metadata type, the `OperatorResumePayload` subtype of `ResumePayload`, the `ApiOperatorPauseController` REST endpoints, the `pause` ACL action on `execution` resources, the `execution.waiting-operator-pause` notification event, and the minimum UI affordance (pause/resume buttons on the execution detail page).

Does not own:
- Mid-step suspension. Pause takes effect at step boundaries only.
- Sub-workflow pause. Only top-level workflows can be paused in v1.
- Permanent audit trail of pause/resume events. Operational actions are observable via existing status history and notification events; a dedicated `ExecutionPauseAudit` table is deferred.

---

## 2. Vocabulary

| Term | Meaning |
|---|---|
| **Operator-pause** | A suspension requested by a human operator (via API or UI) rather than from within a step plugin. Takes effect at the next step boundary. |
| **Pause request** | The act of setting `pause_requested = true` on an Execution row, signaling the engine to synthesize a suspension at the next boundary. |
| **Boundary** | The moment after a step result is observed by `EngineWorkflowExecutor`'s aggregation loop and before the next step begins. The only safe point at which the engine can synthesize an external suspension. |

---

## 3. Behavior contract

### Pause flow

1. Operator clicks "Pause" on the execution detail page, OR calls `POST /api/{v}/execution/{id}/pause` directly.
2. `ApiOperatorPauseController.pause`:
   - Authorizes caller against `pause` ACL action on the execution.
   - Rejects if execution `status != 'running'` → 409.
   - Rejects if execution is a sub-workflow invocation → 409.
   - Rejects if the workflow has no more steps after the currently-executing one (single-step workflow OR paused request arrived while final step is running) → 409 with reason `no-remaining-boundary`.
   - Writes `pause_requested = true` (with optional `pause_requested_by`, `pause_requested_at`, `pause_reason` if we capture them as columns, or inline in `suspend_metadata` when the synthesis happens).
   - Returns 200 with `{paused: true, effectiveAfter: "next step boundary"}`.

   **Decision on single-step / final-step workflows:** the API rejects with 409 rather than accepting optimistically. Rationale: providing clear feedback at request time is better than a silent no-op. The engine can cheaply introspect the workflow's step count + current step index at API time via the in-memory execution reference or a DB read of the job definition.

3. **Engine boundary hook** (new, Wave 4 change to `EngineWorkflowExecutor` or `WorkflowEngineOperationsProcessor`): after each step's result is processed in the aggregation loop, the engine reads the current Execution's `pause_requested` flag. If true AND the workflow has remaining steps:
   - Synthesizes a `SuspendedStepResult` with an internally-constructed `SuspendRequest` whose metadata is `{type: "operator-pause", requestedBy: <caller>, requestedAt: <timestamp>, reason: <optional>}`.
   - Treats the synthesized result as if the engine itself were a step plugin — follows the normal suspend path (spec §6.1 of the primitive).
   - Atomically clears `pause_requested = false` as part of the suspend DB transaction.
4. Execution enters `waiting` with `suspend_metadata.type = "operator-pause"`.
5. Notification event `execution.waiting-operator-pause` fires per spec §9.5 of the primitive (from the node that persisted the suspend).

### Resume flow

1. Operator clicks "Resume" on the execution detail page, OR calls `POST /api/{v}/execution/{id}/resume` directly.
2. `ApiOperatorPauseController.resume`:
   - Authorizes caller against `pause` ACL action.
   - Rejects if execution `status != 'waiting'` → 409.
   - Rejects if `suspend_metadata.type != 'operator-pause'` → 409 (confirmation suspensions cannot be resumed via this endpoint; use `/confirm`).
   - Constructs `OperatorResumePayload{resumedBy, resumedAt, comment}`.
   - Calls `ExecutionResumeService.markResumeReady(executionId, payload)` in the same transaction.
   - Returns 200 on success, 409 if `markResumeReady` reports the row is no longer waiting.
3. Polling worker picks up the row on its next tick, claims atomically, invokes `resumeExecution`.
4. On resume, the engine **does not re-invoke any step**. The synthesized suspension was not tied to a step — it was between steps. The engine simply continues from the step after the one whose boundary triggered the pause.

### Single-step and final-step semantics

The engine boundary hook only fires between steps. Consequences:

- **Single-step workflow:** the flag is set at the API layer but the only possible "boundary" is after the sole step, which is also workflow termination. The API pre-rejects with 409 to give clear feedback.
- **Pause arrives during the final step:** same situation — the only remaining boundary is workflow termination. API pre-rejects with 409.
- **Pause arrives during the next-to-last step:** boundary exists (between the next-to-last and final step). API accepts, engine pauses after next-to-last completes, last step runs after resume.

The rejection message names the reason so operators can act accordingly (e.g., `"no remaining step boundary; execution is on the final step"`).

### Cluster-mode behavior

The `pause_requested` flag is in the shared DB. Any node can read it. When the pause request arrives on node B but the execution is running on node A:

- `ApiOperatorPauseController.pause` on node B writes the flag to the shared DB.
- Node A's engine reads the flag on its next boundary check.
- Node A's engine synthesizes the suspension locally, persists it, clears the flag.
- The cross-node invalidation delay is bounded by the frequency of boundary checks — one per step. No explicit cluster messaging required.

**Race:** the pause request arrives after the workflow's final boundary has already been crossed (execution is terminal-writing in progress). The API pre-check catches this (`status != 'running'` → 409) unless the status transition is in flight. In that narrow race window the write succeeds but is never observed; the flag is cleared on terminal transition per I11 of the primitive. Acceptable — documented as a benign race.

---

## 4. OperatorResumePayload

New `ResumePayload` subtype:

```java
package com.dtolabs.rundeck.server.suspend.operatorpause;

public interface OperatorResumePayload extends ResumePayload {
    String getResumedBy();
    Instant getResumedAt();
    String getComment();  // may be null

    @Override default String getType() { return "operator-resume"; }
}
```

Jackson registration: `"type": "operator-resume"` discriminator. Registered as a named subtype of `ResumePayload` alongside `ConfirmationPayload` via the same subtype registration mechanism (spec §14 Q5 of the primitive).

**Note:** there is no symmetric `OperatorPausePayload` — at pause time, the data lives in `suspend_metadata` as a flat JSON object `{type: "operator-pause", requestedBy, requestedAt, reason}`, not as a polymorphic payload. Only the resume side needs polymorphism (it flows through `resume_payload` which is polymorphic by design).

---

## 5. Persistence

New column on `rundeck_execution` (shipped in Wave 2 of the cycle alongside the other new columns):

```sql
ALTER TABLE rundeck_execution ADD COLUMN pause_requested BOOLEAN DEFAULT FALSE NOT NULL;
```

Optional: also capture `pause_requested_by`, `pause_requested_at`, `pause_reason` as separate columns for easier querying. Alternative: keep them in memory from the API call and serialize into `suspend_metadata` at synthesis time, leaving the DB column as just a boolean flag.

**Decision:** keep the DB column as just `pause_requested BOOLEAN`. The metadata (who, when, why) is carried in a transient in-memory reference from the API call that sets the flag, and read at synthesis time by the engine. If the process crashes between `POST /pause` and the next boundary, the flag is set but the metadata is lost — acceptable because the engine will still synthesize the suspension with a `reason: null` fallback and continue.

**Clearing the flag:**

- Cleared atomically with the transition to `waiting` when the engine observes and acts on it (§3 pause flow step 3).
- Cleared on terminal transition per I11 if never acted on (e.g., `POST /pause` sets the flag but the execution reaches terminal state via a different path before the engine checks).

### Not adding for v1

- `ExecutionPauseAudit` table (permanent audit trail). Operational convenience, not a compliance requirement like `ExecutionConfirmation`. Deferred to a follow-up cycle if operators ask for it.

---

## 6. API contract

Two new endpoints in a new `ApiOperatorPauseController` (package TBD).

### POST `/api/{v}/execution/{id}/pause`

**Request body** (optional):

```json
{"reason": "maintenance window until 14:00"}
```

**Authorization:** `pause` ACL action on the execution resource.

**Preconditions** (checked in order):

1. Execution exists → else 404.
2. Execution `status == 'running'` → else 409 with reason `not-running`.
3. Execution is not a sub-workflow invocation → else 409 with reason `sub-workflow`.
4. Workflow has at least one step boundary remaining after the currently-executing step → else 409 with reason `no-remaining-boundary`.

**Action:** writes `pause_requested = true` in a single DB transaction; captures `reason` into an in-memory reference for the engine to retrieve at synthesis time (accepting the post-crash null-reason fallback per §5).

**Response:** 200 on success: `{"paused": true, "effectiveAfter": "next step boundary"}`.

### POST `/api/{v}/execution/{id}/resume`

**Request body** (optional): `{"comment": "resumed after maintenance completed"}`.

**Authorization:** `pause` ACL action on the execution resource.

**Preconditions:**

1. Execution exists → 404.
2. `status == 'waiting'` → 409.
3. `suspend_metadata.type == 'operator-pause'` → 409 with reason `not-operator-pause` (confirmation suspensions cannot be resumed via this endpoint).

**Action:** construct `OperatorResumePayload`, call `ExecutionResumeService.markResumeReady(id, payload)`, return the result.

**Response:** 200 on success: `{"resumed": true, "resumedBy": <caller>, "resumedAt": <timestamp>}`.

### GET `/api/{v}/execution/{id}/pause/status`

**Authorization:** `read` ACL action.

**Response:** current pause state.

```json
{
  "pauseRequested": true,
  "currentStatus": "running",
  "suspendType": null,
  "requestedBy": "alice",
  "requestedAt": "2026-04-14T15:00:00Z",
  "reason": "maintenance window",
  "effectiveAfter": "next step boundary (currently executing step 2 of 4)"
}
```

When the execution has transitioned to waiting due to operator-pause:

```json
{
  "pauseRequested": false,
  "currentStatus": "waiting",
  "suspendType": "operator-pause",
  "requestedBy": "alice",
  "requestedAt": "2026-04-14T15:00:00Z",
  "waitStartedAt": "2026-04-14T15:02:30Z",
  "callerCanResume": true
}
```

`callerCanResume` is true if the requesting user has `pause` ACL. Used by UI button enable/disable.

---

## 7. ACL action

New action: **`pause`** on resource kind `execution` (project-scoped).

This action covers BOTH pause and resume (§3). Rationale: an operator who can pause should be able to undo their own action; splitting into two actions adds policy complexity without clear benefit.

Distinct from existing `kill` action: `kill` is immediate termination with no recovery; `pause` is a graceful cooperative park at the next boundary with explicit resume. Operators may have authority over one but not the other (e.g., on-call gets pause but not kill for production deploys).

### Machinery to update (shared with confirm plugin Wave 5)

- `AuthConstants.ACTION_PAUSE = "pause"`.
- Authorization policy engine recognizes `pause` on `execution` resource.
- aclpolicy.yaml schema documentation.
- Default aclpolicy.yaml in distribution: add `pause` to `admin` role only.

### Example policy

```yaml
description: Release engineers can pause/resume deploys in ops-deploy
context:
  project: ops-deploy
for:
  execution:
    - equals:
        group: deploy
      allow: [read, view, pause]
by:
  group: release-eng
```

---

## 8. Notification event

New event type: **`execution.waiting-operator-pause`**.

**Fires when:** engine synthesizes an operator-pause suspension (§3 pause flow step 3 → 5).

**Payload:**

```json
{
  "eventType": "execution.waiting-operator-pause",
  "executionId": 42,
  "jobId": "...",
  "jobName": "prod-deploy-v4",
  "project": "ops-deploy",
  "requestedBy": "alice",
  "requestedAt": "2026-04-14T15:00:00Z",
  "reason": "maintenance window",
  "pausedAt": "2026-04-14T15:02:30Z",
  "resumeUrl": "https://rundeck.prod/project/ops-deploy/execution/follow/42"
}
```

**Consumers:** existing notification plugins (email, Slack, webhook) subscribe via job definition notifications, identical to `waiting-confirmation` event flow (§10 of confirm spec). Use case: "team Slack channel gets a message when any prod deploy is paused, with the reason, so on-call knows."

---

## 9. Minimum UI affordance

**Execution detail page** (`/project/{p}/execution/follow/{id}`):

When `status = 'running'`:
- "Pause" button next to existing "Abort" button.
- Enabled if current user has `pause` ACL; disabled with tooltip otherwise.
- Click opens a small dialog with optional `reason` textarea + "Pause" confirm button.
- Confirm calls `POST /pause`; on success, page refreshes; on 409 shows the reason.

When `status = 'waiting'` AND `suspend_metadata.type == 'operator-pause'`:
- Display "paused by X at Y with reason Z" banner.
- "Resume" button (primary styled), enabled if user has `pause` ACL.
- Optional `comment` textarea on resume.
- Click calls `POST /resume`; on success, page reloads to `running`.

When `status = 'waiting'` AND `suspend_metadata.type == 'confirmation'`:
- Show confirmation affordances per confirm-plugin spec §11.
- Do NOT show the operator-pause Resume button (it would 409 anyway).

**Execution list page** (per user direction: "waiting is visible as a filterable state"):
- The existing status filter dropdown includes `waiting`.
- Rows in `waiting` state render the status string; optionally show a small icon indicating the suspend type (`operator-pause` → pause glyph, `confirmation` → confirm glyph). Icon choice is implementation detail for Wave 6.
- No per-row pause/resume buttons — those live on the detail page.

**Not in v1:**
- Dashboard widget "executions I can resume."
- Header notification bell / inbox center.
- Bulk pause/resume UI.
- Pause-at-time scheduling.

---

## 10. Interaction with confirmation suspensions

Both suspension types can occur in the same workflow execution, but sequentially, not simultaneously.

**Example scenario:** workflow has step 1 (build), step 2 (confirm), step 3 (deploy), step 4 (smoke-test).

| # | T | Event |
|---|---|---|
| 1 | 0 | alice runs `prod-deploy-v4`. |
| 2 | 0 | Step 1 begins. |
| 3 | 3m | alice realizes a maintenance window starts in 5 min. Calls `POST /pause` with reason "waiting for maintenance window to end". |
| 4 | 3m | `pause_requested = true`. |
| 5 | 5m | Step 1 finishes. Engine checks `pause_requested`, synthesizes operator-pause. Execution enters `waiting` with `suspend_metadata.type = "operator-pause"`. `pause_requested` cleared. Notification fires. |
| 6 | 3h | Maintenance window ends. alice calls `POST /resume`. |
| 7 | 3h + 2s | Polling worker claims, resumes. Engine continues to step 2 (confirm). |
| 8 | 3h + 2s | Confirm step calls `context.suspend(...)`. `suspend_metadata.type = "confirmation"`. Notification `waiting-confirmation` fires. |
| 9 | 3h 10m | bob approves via `POST /confirm`. |
| 10 | 3h 10m + 2s | Execution resumes, step 3 runs. |
| 11 | 3h 25m | Step 3 completes. |
| 12 | 3h 25m | Engine checks `pause_requested` at step 3/4 boundary → false. Continues to step 4. |
| 13 | 3h 28m | Step 4 succeeds, workflow completes. |

**Key properties:**

- At any moment, an execution has at most one active suspension. The suspension type is stored in `suspend_metadata.type`.
- Operator-pause and confirmation are not stackable; they are strictly sequential.
- `POST /pause` while the execution is already `waiting` → 409 (not running). Operators cannot "double-park" an execution.
- `POST /confirm` on an operator-pause row → 409 (wrong type). Operator-pause resolves via `/resume`, not `/confirm`.
- `POST /resume` on a confirmation row → 409 (wrong type). Confirmations resolve via `/confirm`.
- Abort (`POST /kill`) works on both types: transitions directly to `aborted` via the primitive spec I9.

---

## 11. Not in scope (v1)

- Mid-step pause. Only step-boundary pause.
- Sub-workflow pause. Child-workflow pause rejected at API time.
- Scheduled pause ("pause at 14:00"). Only immediate pause.
- Auto-pause based on conditions ("pause if CPU > 80%"). Declarative triggers are a different feature.
- Permanent pause/resume audit table. Operational action; defer.
- Pause propagation ("pause all executions of this job"). Per-execution only.
- Bulk pause/resume. Individual endpoint only.
- Parent-paused-by-child cascade. Not applicable — children cannot suspend in v1 anyway (primitive I7).
- UI dashboard / inbox / notification center for pause events. Minimum UI only.
- `rd pause` CLI subcommand. Follow-up cycle.

---

## 12. Test strategy

### Unit tests (core)

- `OperatorResumePayload` Jackson round-trip with `type="operator-resume"` discriminator.
- Engine boundary hook: synthesizes `SuspendedStepResult` with correct metadata when `pause_requested = true`.
- Engine boundary hook: no synthesis when `pause_requested = false` (performance regression guard: non-pausing execution path must not regress measurably).
- Engine boundary hook: no synthesis on the last step of a workflow (no boundary).
- Engine boundary hook: synthesized suspension reuses the normal suspend path (spec §6.1).

### Grails tests

- `ApiOperatorPauseController.pause` happy path.
- `pause` 409 on `status != 'running'`.
- `pause` 409 on sub-workflow.
- `pause` 409 on single-step / final-step workflow (`no-remaining-boundary`).
- `pause` 403 on missing `pause` ACL.
- `resume` happy path.
- `resume` 409 on `status != 'waiting'`.
- `resume` 409 on `suspend_metadata.type != 'operator-pause'`.
- `resume` 403 on missing `pause` ACL.
- `GET /pause/status` returns correct state in both running-with-pending-pause and waiting-after-pause scenarios.
- ACL `pause` action enforced by policy engine.
- `waiting-operator-pause` event fires on synthesis.
- `pause_requested` flag cleared on successful suspend-synthesis.
- `pause_requested` flag cleared on terminal transition per I11.

### Integration tests

- **Full pause/resume flow:** start multi-step workflow → `POST /pause` → execution transitions to `waiting` at next boundary → `POST /resume` → remaining steps execute → `status = succeeded`.
- **Pause + confirmation sequential:** scenario from §10 — pause first, then confirm; both observed sequentially; workflow completes.
- **Pause rejected on single-step workflow:** `POST /pause` returns 409 with reason `no-remaining-boundary`.
- **Pause rejected on sub-workflow:** 409.
- **Pause rejected on waiting execution:** 409.
- **Resume rejected on confirmation-type suspension:** 409.
- **Two racing `POST /pause`:** both return 200 (idempotent flag write), engine synthesizes one suspension.
- **Cluster-simulating:** pause request on simulated node B while execution runs on simulated node A; node A's engine observes flag, synthesizes pause; resume from either node.
- **Pause during final step:** `POST /pause` during the last step → 409 `no-remaining-boundary` (the engine determines there is no next step boundary).
- **Pause event notification:** mock notification plugin receives `waiting-operator-pause` event.
- **Pause metadata survival:** after pause and resume, `suspend_metadata.requestedBy / requestedAt / reason` are correctly populated during the waiting window and cleared on terminal transition.
- **Abort of an operator-paused execution:** `POST /kill` on a row with `status = 'waiting'` and `suspend_metadata.type = 'operator-pause'` → transitions to `aborted`, log closed normally, notifications fire.

---

## 13. Open questions

1. **Should `pause` be a single ACL action covering both pause and resume**, or split into `pause` + `resume-pause`? §7 leans single. Leaves open the possibility that some operators can start pauses but not unstick them; unclear if a real use case exists.
2. **Should the reason be captured as a DB column** (`pause_requested_reason`) or kept in memory between API call and synthesis? §5 leans in-memory with null fallback. Simpler schema; small risk of losing the reason on rare crash windows.
3. **Should `GET /pause/status` be merged into the existing execution-detail API** (`GET /api/{v}/execution/{id}`) instead of being a separate endpoint? Leaning separate for clarity and to allow finer-grained authorization (read vs. pause).
4. **Pause during final step — reject or accept with warning?** §6 says reject with 409. Alternative: accept and silently complete (the flag is ignored). Leaning reject for clear API feedback.
5. **Notification routing for `waiting-operator-pause`:** the event fires from the node that persisted the suspension (per primitive §9.5), which is the node running the engine (where the boundary check happened). Consumers should not assume the event fires from the node that received the API call. Document explicitly.

---

**⏸ PAUSE with other Phase 2 artifacts. Review together.**
