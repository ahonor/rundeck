# A new plugin SPI for human-in-the-loop workflow steps

**Proposed SPI:** `InteractionPlugin`
**Audience:** Rundeck weekly architecture meeting, 2026-05
**Presenter:** Alex Honor
**Format:** ~15–20 min, two live demos
**Status:** Implemented and live-tested on a Rundeck OSS fork — `github.com/ahonor/rundeck` branch `cycle/workflow-suspend-resume`. As-built spec at `docs/confirm-pause-resume-spec.md` in the same branch.

---

## What this is

A proposal to add a new first-class plugin SPI category for **workflow steps that solicit structured input from a human mid-execution**. Sits alongside `StepPlugin`, `NotificationPlugin`, `LogFilterPlugin`, and the rest.

The SPI is designed to support a **family of human-input primitives**, not just yes/no confirmation. Today's prototype ships only the `confirm` primitive (approve/deny gate). The SPI shape is intentionally designed so the same plugin category can later carry `choose`, `ask`, `review`, `attest`, `escalate`, and `rank` without breaking changes — see the next section.

To make the SPI work, supporting engine primitives were also built: a generic suspend/resume SPI, log writer checkpoint hook, and execution lifecycle changes. These primitives are themselves first-class extension points and are reusable for any future plugin that needs to pause a workflow.

## Why

Today, Rundeck workflows can't pause for human input. The current workaround — split into two jobs, send a notification, manually trigger the second job after a human acts — is fragile:

- State is lost between jobs (data context, output context, schedule context)
- Audit trail is fragmented across multiple executions
- The workflow visualization shows two unrelated runs instead of one paused workflow
- Notification/webhook delivery isn't reliable; polling adds latency
- Doesn't compose with workflow-level retry, error handling, or observability

Rundeck has prior art for some of this surface (Job Options solicit typed input at job start), but Job Options can't be invoked mid-workflow, can't carry rich context, and can't produce structured signed evidence. A first-class human-input SPI generalizes that capability and gives Rundeck users a coherent paused-workflow experience for the full input family.

## The human-input primitive family (target scope)

The SPI is designed to support these primitives. Only `confirm` is implemented today.

| Primitive | Shape | Example | Status |
|---|---|---|---|
| `confirm` | yes/no approval | "Deploy v2.1.0 to production?" | **Prototype shipped** on the fork |
| `choose` | pick one of N options | "Roll back to which version: v2.3.1, v2.3.0, or v2.2.5?" | Designed-for, not built |
| `ask` | typed structured input | "What's the change ticket ID?" (string, regex-validated) | Designed-for, not built |
| `review` | proceed / modify / abort a plan | Show a diff or proposed change set | Designed-for, not built |
| `attest` | signed witness, optionally counter-signed | "I personally verified the backup completed at T" | Designed-for, not built |
| `escalate` | policy-override request to a higher approver pool | Break-glass during incident | Designed-for, not built |
| `rank` | order items by priority | "Rank these remediation actions by which to try first" | Designed-for, not built |

Each primitive has a distinct request/response shape (a `confirm` carries a message and criticality; an `ask` carries a validation schema; an `attest` carries countersign candidates; etc.). The SPI is designed so plugins declare which primitives they support and the engine dispatches accordingly — same pattern as the rx HIL proposal's umbrella surface.

This list is not speculative. It comes from the rx HIL primitive family which the Rundeck-side SPI is intended to fulfill on Rundeck's behalf as one of the first-wave providers.

**Why show the family now:** the SPI shape needs to accommodate all seven primitives at introduction so later primitive implementations don't require breaking SPI changes. Only the SPI shape is committed at v1; primitive implementations follow incrementally as they're scoped and built.

A related question for v1: do we publicly commit to the full primitive family at SPI introduction, or commit only to `confirm` and treat the rest as design intent? The SPI shape supports either; the choice affects what plugin authors and integrators see as the public contract. This brief assumes the latter — committing visibly only to what's built, with the family scope visible in the SPI design.

## Proposed SPI

```java
@Experimental
public interface InteractionPlugin {
    /**
     * Declare which primitives this plugin handles. The engine dispatches
     * a request to the plugin only if the primitive is supported.
     */
    Set<HILPrimitive> supportedPrimitives();

    /**
     * Called when the workflow reaches this step. Returns a typed request
     * carrying the primitive type and primitive-specific payload. The engine
     * suspends the workflow at the next step boundary.
     */
    HILRequest prepareRequest(
        PluginStepContext context,
        Map<String, Object> configuration);

    /**
     * Called when a structured response arrives via the resume path.
     * Plugin can update output context, log details, or throw on negative
     * outcomes (denied, timeout, error).
     */
    void onResponse(
        PluginStepContext context,
        HILResponse response) throws StepException;
}

public enum HILPrimitive { CONFIRM, CHOOSE, ASK, REVIEW, ATTEST, ESCALATE, RANK }
```

`HILRequest` and `HILResponse` are envelope types with primitive-specific payloads. Two ways to model the per-primitive payload variation:

- **(A) Unified envelope, polymorphic payload** — one `HILRequest` class with a `primitive` field and a generic payload map, plus convenience accessors. Lighter ceremony, plugin authors do their own dispatch on the primitive type.
- **(B) Per-primitive subtypes** — `ConfirmRequest`, `ChooseRequest`, `AskRequest`, etc. (extending `HILRequest`), with corresponding response types. More classes, type-safe per primitive. Matches the rx Go provider pattern (per-primitive handler interfaces).

Both work. I lean toward (B) for type safety — per-primitive subtypes give plugin authors compile-time validation of the payload shape they're producing or consuming, which matters more as the primitive count grows. The trade is heavier ceremony in exchange for fewer plugin-author bugs. Happy to be talked out of it.

A related typing question: today's prototype carries confirmation-specific fields directly on `SuspendRequest` (`decisionSet`, `requiredConfirmerRoles`, `criticality`, `source`). For the family-scoped SPI those would more naturally live on a `HILRequest` extension of `SuspendRequest`, with primitive-specific subclasses underneath. Whether to promote the typed wrapper or keep the well-known fields on `SuspendRequest` directly is open.

Plugin authors implement only this interface. The engine handles suspending the workflow, persisting state, exposing the REST endpoint for responses, ACL enforcement, audit, the resume worker, and log continuity. All primitive payloads are Jackson-polymorphic via `JacksonSubtypeRegistrar`, so third parties can register their own primitive shapes if the family needs to grow.

## What's added (the concrete delta)

A guide to what's in the diff, by surface area. Roughly ~10k lines across these categories. **Backward compatibility:** all new code paths are opt-in. Existing executions that don't suspend exhibit no behavior change. Schema migration adds columns only — no existing column types or constraints modified.

### REST API

New endpoints in `rundeckapp/grails-app/controllers/rundeck/controllers/`:

- `POST /api/{v}/execution/{id}/pause` — operator-initiated pause; suspends at next step boundary.
- `POST /api/{v}/execution/{id}/resume` — operator-initiated resume with `OperatorResumePayload`.
- `POST /api/{v}/execution/{id}/confirm` — submit confirmation decision (`ConfirmationPayload`); enforces role match against `requiredConfirmerRoles` configured on the suspend request.
- `GET /api/{v}/execution/{id}/confirm/status` — current waiting state, what's being asked, who can confirm.
- `GET /api/{v}/execution/{id}/confirmations` — audit list of past decisions on this execution.

The generic `/resume` endpoint accepts any registered `ResumePayload` subtype via Jackson polymorphic dispatch, so future primitives (`choose`, `ask`, etc.) plug in without new endpoint surface.

### ACL

New actions on the Execution object, integrated with the existing Rundeck ACL framework — no new ACL contexts:

| Action | Purpose |
|---|---|
| `pause` | Operator-initiated pause on a running execution |
| `resume` | Operator-initiated resume on a waiting execution |
| `confirm` | Submit a confirmation decision (further filtered by `requiredConfirmerRoles`) |

Each action is checked at the controller layer. Audit rows are written for every successful action.

### GUI

In `rundeckapp/grails-app/assets/` and `views/`:

- **Pause / Resume buttons** on the execution show page (`show.gsp`) — visible only to users with the corresponding ACL action.
- **"Waiting" status indicator** on the executions activity list (pause icon, distinct styling).
- **Confirmation panel** that slides in on the execution detail page when the execution is waiting for a decision — shows the message, criticality, decision options, comment field, and submit button.
- **Step state indicator** for the currently-suspended step shows "waiting" rather than the usual running/succeeded/failed states.
- **Confirmation history** rendered on the execution detail page (audit display of past decisions on this execution).

**Plugin UI delivery — current approach.** The confirm plugin's UI is shipped as a `UIPlugin` (CSS + JS bundled with the plugin jar, loaded at `execution/show`). The JS polls the confirm status API, then injects the panel DOM into `#execution-show-content` (with fallbacks to `.execution-show` and `#section-content`). Mutual exclusion across confirm-UI plugins is managed by a shared `data-rundeck-confirm-ui` data attribute marker — whichever plugin renders first wins; others detect the marker and skip.

This works but is an implicit convention rather than a formal contract. **A formalized carve-out is on the table** — for example, a designated container element in `show.gsp` (`<div id="rundeck-hil-panel-container">`) that HiL plugins target deterministically, or a Vue-native `<ui-socket section="execution-hil">` binding that matches Rundeck's modern plugin-UI direction. Open: should the existing UIPlugin pattern be the contract for HiL UIs, or should a first-class carve-out be introduced as the SPI surface for plugin-delivered dialogs?

### Database

Liquibase migration in `rundeckapp/grails-app/migrations/`. New columns on the `execution` table:

| Column | Purpose |
|---|---|
| `server_nodeuuid` (nullable) | Atomic claim coordination across HA nodes |
| `suspended_at` | Timing |
| `wait_started_at` | When the wait began (for timeout calculations) |
| `suspend_deadline` | Hard deadline if the suspend request specified a timeout |
| `resume_ready` (boolean) | Flag for the polling worker to pick up |
| `resume_payload` (text/JSON) | Serialized response payload waiting to be applied |
| `resume_attempt_count` (int) | Retry / observability counter |

New table: `execution_confirmation` — audit row per confirmation decision (id, execution_id, confirmed_by, decision, comment, confirmed_at, …).

### App services

- **`ExecutionResumeService`** (new Grails service) — polling worker, atomic claim logic, resume orchestration, payload deserialization.
- **`ExecutionUtilService`** (modified) — suspend marker, log writer chain walk for checkpoint support.
- **`ExecutionService`** (modified) — wait/resume integration with the existing execution lifecycle.

### Core engine

- **`EngineWorkflowExecutor`** — added `executeWorkflowResume` entry point that rebuilds context from a checkpoint and replays state listener events for already-completed steps.
- **`StepOperation`, `StepPluginAdapter`** — detect `pendingSuspension` after step execution and surface the suspend signal to the engine.
- **`BaseWorkflowExecutor`, `NodeFirstWorkflowExecutor`** — handle suspend results (no behavior change for non-suspending workflows).
- **Rule engine** — new `STEP_SUSPENDED_END_WORKFLOW` rule for clean loop exit.
- **`WorkflowExecutionStateListenerAdapter.replayCompletedStep()`** — used by the resume path to pre-populate the state model with steps that completed before suspend.

## Supporting infrastructure (also new)

Generic primitives that the plugin SPI is built on. Each is a first-class extension point in its own right. All new public types are annotated `@Experimental` to signal that the API may change before promotion to stable — the right duration to keep that designation in place is itself worth your read, especially given six of the seven primitives are still designed-for-not-built.

**Suspend/Resume SPI** (`core/.../workflow/suspend/`)
- Core types: `SuspendRequest`, `ResumePayload`, `SuspendedStepResult`, `ExecutionCheckpoint`.
- `JacksonSubtypeRegistrar` is the extensibility hook — third-party plugins register their own `ResumePayload` subtypes (and primitive payload shapes within the HIL family) at plugin-load time, so the protocol is open to extension without changing core. Whether the registrar pattern is the right extension mechanism — or whether something more declarative would be better — is worth your read.

**Engine integration**
- Rule-engine handles `STEP_SUSPENDED_END_WORKFLOW` for clean loop exit
- Resume entry point rebuilds execution context from checkpoint
- Step-boundary suspend only — no mid-step interruption semantics

**Log writer checkpoint SPI**
- `CheckpointableStreamingLogWriter` — opt-in interface for log writers.
- Recursive walk of `FilterStreamingLogWriter` chains and `MultiLogWriter` fan-outs.
- Graceful fallback when no checkpointable writer is found: normal close (writes `^END^` footer) so existing third-party log writer plugins continue to work. Whether that fallback is acceptable as a v1 contract — or whether existing log writer plugins should be required to opt in for the cleanest experience — is open.

**Persistence + multi-node coordination**
- Schema migration adds suspend-state columns to the execution table. Multi-DB compatibility (H2, MySQL, Postgres) is what's been tested; column placement and rollback strategy are worth scrutinizing.
- Atomic claim on resume via `UPDATE … WHERE serverNodeUUID IS NULL` — DB-level race resolution for HA deployments. A heartbeat timeout to release stuck-in-progress claims isn't yet implemented; whether that hardening is needed for v1 is worth your read.
- Polling resume worker (any Rundeck node can pick up a ready-to-resume execution).

## Layer view

```
┌─────────────────────────────────────────────────────────┐
│ Plugin layer                                            │
│   InteractionPlugin implementations (jar plugins)      │
│   — reference confirm-only plugin on the fork           │
│   — future: full HIL family (choose/ask/review/         │
│     attest/escalate/rank), external-signal-wait, …      │
├─────────────────────────────────────────────────────────┤
│ App layer (rundeckapp / Grails)                         │
│   Operator pause/resume UI + REST                       │
│   Generic /resume + per-primitive response endpoints    │
│   Polling resume worker (atomic claim) + audit          │
│   Schema migration                                      │
├─────────────────────────────────────────────────────────┤
│ Core engine (workflow + execution lifecycle)            │
│   Step-boundary suspend handling                        │
│   Checkpoint serialize/restore                          │
│   Resume entry point (executeWorkflowResume)            │
├─────────────────────────────────────────────────────────┤
│ Core SPI (public API surface, all @Experimental)        │
│   InteractionPlugin (proposed) + HILPrimitive enum     │
│   HILRequest / HILResponse + per-primitive payloads     │
│   SuspendRequest, ResumePayload, ExecutionCheckpoint    │
│   JacksonSubtypeRegistrar                               │
│   CheckpointableStreamingLogWriter                      │
└─────────────────────────────────────────────────────────┘
```

Two consumers of the supporting primitives today:

1. **`InteractionPlugin`** — first plugin SPI to use them; only `confirm` primitive shipped
2. **Operator pause/resume in core** — same primitives, no plugin needed

## What's verified today

- E2E approve **and** deny paths via REST API and UI (`confirm` primitive only)
- Operator pause/resume cycle with a multi-step workflow, including log continuity
- Restart recovery: pending suspended executions survive Rundeck restart and remain resumable
- Multi-node atomic claim: tested concurrent claim attempts; no double-execution
- Log writer checkpoint with the default FS log writer; graceful fallback path tested

**Not yet built:** the other six primitives (`choose`, `ask`, `review`, `attest`, `escalate`, `rank`). The SPI is shaped to accommodate them; their implementations are scoped work for later.

## What you'll see live

1. **Operator pause/resume** — a running multi-step workflow paused via UI button at the next step boundary, then resumed. Log continuity preserved across the suspend.
2. **Confirmation plugin (`confirm` primitive)** — workflow with a confirm step pauses waiting for a decision; decision submitted via UI; workflow resumes. Approve and deny paths shown.

## What this brief is **not** asking for

- Not a merge decision today
- Not a commitment to a PR review timeline
- Not a final SPI shape — open to your input on all of the above
- Not the full primitive family in v1 — `confirm` is what's built; the SPI shape is what's being formalized

I'm here to share the work, hear your concerns, and understand what would need to change before this could be considered seriously. The OSS/commercial split is a separate conversation I've been having with the PM and is out of scope for this meeting.

## Code & reference

**Branch and diff:**

- `github.com/ahonor/rundeck` → `cycle/workflow-suspend-resume`
- Compare against upstream: `github.com/rundeck/rundeck/compare/main...ahonor:rundeck:cycle/workflow-suspend-resume`

**Read first** (the most polished, accurate accounts of what shipped):

- `docs/confirm-pause-resume-spec.md` — as-built spec; the single best summary of the feature.
- `docs/dev-guide-suspend-resume.md` — dev guide for building, exercising, and verifying the feature.
- Appendix A below — rx HIL wire protocol reference.

**Working artifacts from the development cycle** — drafts, not polished engineering docs. Useful if you want to dig into design rationale, alternatives considered, or test methodology:

- `docs/specs/workflow-suspend-resume.md` — engine-level spec for the suspend/resume primitive (lifecycle into and out of `waiting`).
- `docs/specs/confirm-workflow-step.md` — spec for the built-in confirm plugin: persistence, API, ACL, UI affordance.
- `docs/specs/operator-pause.md` and `docs/specs/operator-pause-resume.md` — operator pause/resume design (Phase 2 draft + consolidated v1.0).
- `docs/specs/test-plan-workflow-suspend-resume.md` — test methodology and success criteria.
- `docs/walkthroughs/workflow-suspend-resume.md` — concrete scenarios traced end-to-end against the spec, with gap analysis.
- `docs/audits/workflow-suspend-resume.md` — Phase 1 feasibility audit of Rundeck's workflow engine; the research that justified the approach.
- `docs/cycles/workflow-suspend-resume.md` — top-level cycle doc tracking what was built across phases.

---

## Appendix A: rx HIL envelope reference

The `InteractionPlugin` SPI is designed to fulfill the rx HIL primitive family wire protocol. For reference, the request and response envelope shapes the SPI must produce and consume:

**Request envelope** (provider receives this):

```json
{
  "kind": "rx.hil/v1",
  "primitive": "confirm",
  "request": {
    "message": "Deploy v2.1.0 to production?",
    "context": {
      "module": "ops",
      "command": "deploy",
      "caller": {
        "id": "alice@company.example",
        "display": "Alice Anderson",
        "trust": "verified",
        "source": "oidc-jwks"
      },
      "args": {"version": "v2.1.0", "env": "production"}
    },
    "criticality": "critical",
    "reason": "quarterly release",
    "timeout": "2h",
    "timeout_action": "deny"
  }
}
```

The `request` payload varies per primitive — `choose` has an options list; `ask` has a validation schema (regex / JSON schema / type constraint); `review` has a structured plan; `attest` has pre-resolved countersign candidates; `escalate` has an approver-group reference; `rank` has items to order. The top-level envelope (`kind`, `primitive`, `request`) is uniform.

**Response envelope** (provider produces this):

```json
{
  "kind": "rx.hil/v1",
  "primitive": "confirm",
  "response": {
    "status": "approved",
    "responder": {
      "id": "bob@company.example",
      "display": "Bob Smith",
      "source": "slack"
    },
    "payload": {"approved": true},
    "evidence": [
      "confirm: approved by bob@company.example via slack #ops-approvals after 2m37s"
    ],
    "timestamp": "2026-04-14T15:42:11Z"
  }
}
```

`status` normalizes across primitives: `approved` / `denied` / `modified` / `timeout` / `error`. Multi-signer primitives (notably `attest` with countersign) carry an `attesters` array alongside the scalar `responder` — each entry has `id`, `display`, `role` (e.g. "requester" / "countersign"), `source`, and `timestamp`.

**Mapping to `InteractionPlugin`:**

- The `request` payload maps to the `HILRequest` returned by `prepareRequest()`.
- The `response` payload maps to the `HILResponse` passed to `onResponse()`.
- The `responder` identity is provided by Rundeck's existing user/role context on the resume path — plugin authors don't authenticate the responder themselves.
- The `evidence` strings produced by the plugin are appended to Rundeck's execution log; the runtime independently records the full envelope to the audit sink.
- Group resolution for primitives that need it (e.g. `attest` countersign candidates) is done by Rundeck's runtime via existing ACL/group lookup — plugin authors don't expand groups themselves.

This separation — auth and group resolution in the runtime, primitive-specific UX in the plugin — keeps plugins thin and makes them portable across identity backends.
