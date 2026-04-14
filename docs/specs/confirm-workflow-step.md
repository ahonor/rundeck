# spec/confirm-workflow-step

**Phase:** 2 (Spec) — companion to `docs/specs/workflow-suspend-resume.md`.
**Status:** Draft pending Phase 2 review.
**Owned surface:** The built-in `ConfirmWorkflowStep` plugin that pauses a workflow pending human confirmation, its persistence model, its API and ACL surface, and its minimum UI affordance.
**Depends on:** `docs/specs/workflow-suspend-resume.md` — this plugin is the reference consumer of the suspend/resume primitive.

This spec is deliberately neutral on the specific decision vocabulary. v1 ships with an `approve`/`deny` decision set, but the plugin, payload, audit domain, ACL action, notification event, and UI affordance are all named with neutral "confirmation" vocabulary so that future decision sets (yes/no, proceed/rollback/investigate, free-text) can land in follow-up cycles without renames.

---

## 1. What the area owns

The `ConfirmWorkflowStep` plugin and everything that exists to support it: its configuration schema, the `ConfirmationPayload` subtype of `ResumePayload`, the `ExecutionConfirmation` audit domain, the `ApiConfirmController` REST endpoints, the `confirm` ACL action on `execution` resources, the `execution.waiting-confirmation` notification event type, and the minimum v1 UI affordance on the execution detail page.

Does not own:
- The suspend/resume primitive itself (see `workflow-suspend-resume.md`).
- Third-party step plugins that consume the suspend/resume primitive for non-confirmation purposes.
- Notification plugin implementations (email, Slack, webhook) — this spec defines the event they subscribe to, not the delivery mechanism.
- UI polish beyond the minimum approve/deny affordance.

---

## 2. Vocabulary

| Term | Meaning |
|---|---|
| **Confirmation** | A human-in-the-loop decision point in a workflow. The generalization of approve/deny prompts, yes/no gates, and other structured decision inputs. |
| **Decision** | The specific value a confirmer provides. v1 decision set: `approve`, `deny`. Future decision sets are plugin-defined. |
| **Confirmer** | The user who submits a decision. Must hold the `confirm` ACL action on the execution, plus any `requiredConfirmerRoles` configured on the step. |
| **Decision set** | The set of valid decisions for a given confirm step. Plugin-defined; v1 hardcoded to `{approve, deny}` at the plugin level but the data model accepts arbitrary strings for future extensibility. |

---

## 3. Plugin class and configuration

**Class:** `com.dtolabs.rundeck.plugin.confirm.ConfirmWorkflowStep`
**Ships in:** Rundeck OSS core (new gradle module `plugins/confirm-plugin` or inlined in an existing core-plugin module; exact location deferred to Phase 3).
**Plugin service:** `WorkflowStep` (workflow-level, not node-level). A node-level variant is not shipped in v1; confirm is a single decision per workflow invocation, not per node.

### Configuration properties

| Property | Type | Default | Description |
|---|---|---|---|
| `message` | string (required) | — | Prompt text shown to confirmers. Supports data-context interpolation via `${option.foo}`, `${data.bar}`. |
| `timeout` | duration | `24h` | Maximum wait before auto-action. Subject to the suspend/resume primitive's `wait_timeout_at` column. |
| `timeoutAction` | enum: `deny` \| `approve` \| `fail` | `deny` | What happens when timeout fires. `deny`/`approve` synthesize a `ConfirmationPayload` with the corresponding decision and `timeout=true`. `fail` throws a step failure with no synthetic decision. |
| `requiredConfirmerRoles` | comma-list of role strings | — (empty) | If set, confirmer must hold at least one of these roles IN ADDITION to the `confirm` ACL action. ANY semantics (one-of), not ALL. |
| `decisionSet` | comma-list | `approve,deny` | Valid decisions for this step. The first decision in the list is the "success path"; other decisions fail the step. v1 accepts only `approve,deny` as the configured value — additional sets reserved for future cycles. |
| `minConfirmations` | integer | `1` | Quorum size. v1 hardcoded to `1`. Multi-confirmer quorum is a future cycle. |

---

## 4. ConfirmationPayload

Subtype of `ResumePayload` (the polymorphic interface defined in `workflow-suspend-resume.md §2.1`).

```java
package com.dtolabs.rundeck.plugin.confirm;

public interface ConfirmationPayload extends ResumePayload {
    /** The decision submitted. Member of the frozen decisionSet. */
    String getDecision();

    /** User id of the confirmer. */
    String getConfirmedBy();

    /** Roles held by the confirmer at confirmation time. */
    List<String> getConfirmerRoles();

    /** Free-text comment provided by the confirmer. May be empty. */
    String getComment();

    /** Wall-clock time at which confirmation was recorded. */
    Instant getConfirmedAt();

    /** True if this is a synthetic payload produced by the timeout sweep. */
    boolean isTimeout();

    @Override
    default String getType() { return "confirmation"; }
}
```

**Jackson serialization:** `"type": "confirmation"` discriminator at the top level. Registered as a named subtype of `ResumePayload` at core deserialization time. Implementation (not spec): the plugin's module registers the subtype via a `@JsonTypeInfo`/`@JsonSubTypes` annotation on `ResumePayload` or via a Jackson module loaded at startup.

---

## 5. Behavior contract

### First invocation (suspend)

1. Plugin's `executeWorkflowStep(context, item)` is called.
2. Plugin reads its configuration properties from `item` via the normal plugin-config resolution path.
3. Plugin resolves `${...}` interpolations in `message` against the current `DataContext`. The resolved message is what gets frozen.
4. Plugin constructs a `SuspendRequest` with:
   - `reason = "awaiting confirmation"`
   - `waitingFor = "user-confirmation"` (or `"user-confirmation:<first-required-role>"` if roles are set)
   - `timeoutMs = <timeout property>`
   - `metadata` (structured, written to `Execution.suspend_metadata`): `{type: "confirmation", message: <resolved>, decisionSet: [...], requiredConfirmerRoles: [...], timeoutAction: "..."}`
   - `payload` (plugin-internal opaque): may be empty for v1; reserved for plugin state the plugin wants to recover on resume.
5. Plugin returns `context.suspend(request)`.

### Resume invocation

1. Plugin's `executeWorkflowStep(context, item)` is called again on the resume node.
2. Plugin reads `context.getResumePayload()` → expected to be a `ConfirmationPayload` (downcast; throws clear error otherwise, which becomes a step failure).
3. Plugin reads the frozen metadata from `context.getSuspendMetadata()` (new accessor — see §6 freeze).
4. Branch on the payload:
   - **Timeout, `timeoutAction=deny`:** log "timed out; no confirmer responded within <duration>; denying per configuration"; throw `StepException("confirmation timed out", ConfirmTimeout)`.
   - **Timeout, `timeoutAction=approve`:** log "timed out; no confirmer responded within <duration>; auto-approving per configuration"; return success.
   - **Timeout, `timeoutAction=fail`:** log "timed out; no confirmer responded within <duration>; failing per configuration"; throw `StepException("confirmation timed out", ConfirmTimeoutFailure)`.
   - **Decision is the first entry of `decisionSet`** (v1: `approve`): log `"confirmed by <confirmer> (roles: <roles>): <comment>"`; return success.
   - **Decision is any other entry of `decisionSet`** (v1: `deny`): log `"rejected by <confirmer> (roles: <roles>): <comment>"`; throw `StepException("confirmation denied: <decision>", ConfirmDenied)`.

### Failure modes

| Condition | Outcome |
|---|---|
| `getResumePayload()` returns non-ConfirmationPayload subtype | Step fails with `ConfirmUnexpectedPayload` reason. |
| Decision is not in the frozen `decisionSet` | Cannot happen via the API (validated at POST time) but possible via direct DB manipulation. Step fails with `ConfirmInvalidDecision`. |
| Metadata missing from `Execution.suspend_metadata` on resume | Step fails with `ConfirmMetadataMissing`. Indicates an upstream bug in the suspend path. |

---

## 6. Configuration freeze

Per walkthrough gap G30 and suspend/resume spec §12 decision 14: the plugin's resolved configuration is **frozen at suspend time**. On resume, the plugin does NOT re-read the job definition. It reads from the frozen metadata stored in the `Execution.suspend_metadata` column.

Rationale: the confirmer provides consent against a specific prompt, decision set, and constraints. A mid-flight edit to the job definition after suspend must not retroactively change what was confirmed. Operators who need to change the prompt after an execution has suspended must abort the execution and rerun it.

**Scope of the freeze:**

- `message` (post-interpolation)
- `decisionSet`
- `requiredConfirmerRoles`
- `timeoutAction`
- `timeout` (duration, also captured as absolute `wait_timeout_at` on the Execution row per suspend/resume spec §2.3)

**Not frozen:**

- Plugin class name — the class must still be loadable on the resume node; freezing the class would prevent in-place plugin upgrades. Walkthrough gap G29's "plugin class not loadable" failure mode applies.
- Plugin version — if the operator upgrades the plugin between suspend and resume, the new version runs, reading the frozen config. The new plugin must tolerate config from its previous version (backwards compat requirement on plugin authors).

---

## 7. Persistence — ExecutionConfirmation audit domain

New Grails domain class:

```groovy
package rundeck

class ExecutionConfirmation {
    Long    id
    Long    executionId          // FK to Execution.id
    String  confirmedBy          // user id of the confirmer (or empty string for timeout)
    String  confirmerRoles       // comma-delimited roles held at confirmation time
    String  decision             // member of the frozen decisionSet (or empty for timeout)
    String  comment              // free text, may be empty
    Date    confirmedAt          // wall-clock write time
    Boolean timeout = false      // true if synthesized by the timeout sweep
    String  stepContext          // which step in the workflow (e.g., "2" or "2/1" for nested)

    static constraints = {
        executionId    nullable: false
        confirmedBy    nullable: true, maxSize: 255
        confirmerRoles nullable: true, maxSize: 1024
        decision       nullable: true, maxSize: 64
        comment        nullable: true, maxSize: 2048
        confirmedAt    nullable: false
        stepContext    nullable: false, maxSize: 64
    }

    static mapping = {
        version false
        table 'rundeck_execution_confirmation'
    }
}
```

**Semantics:**

- Exactly one row is written per confirmation event (human or synthetic timeout).
- Rows are **immutable** once written. No update path.
- Multiple rows may exist for one execution if its workflow contains multiple confirm steps (distinguished by `stepContext`).
- Rows are **permanent audit trail** — they are NOT cleared when the Execution transitions to a terminal state. This is intentional; unlike the suspend-related columns on `Execution` (which invariant I11 clears), confirmation audit entries are kept forever (subject to separate log-retention policy).
- Rows are readable via the API for compliance reporting; a future cycle may add bulk query / export.

**Schema:**

```sql
CREATE TABLE rundeck_execution_confirmation (
    id               BIGSERIAL PRIMARY KEY,
    execution_id     BIGINT NOT NULL REFERENCES rundeck_execution(id),
    confirmed_by     VARCHAR(255),
    confirmer_roles  VARCHAR(1024),
    decision         VARCHAR(64),
    comment          VARCHAR(2048),
    confirmed_at     TIMESTAMP NOT NULL,
    timeout          BOOLEAN NOT NULL DEFAULT FALSE,
    step_context     VARCHAR(64) NOT NULL
);

CREATE INDEX idx_execution_confirmation_execution_id
    ON rundeck_execution_confirmation (execution_id);
```

---

## 8. API contract

**Design principle — API-first, UI-thin-wrapper.** Every capability exposed via the confirm plugin is accessible through the API. The UI does nothing that the API does not. This is a normative constraint, not an incidental property:

- No UI-exclusive capabilities.
- Automation can drive confirmation without any UI. Use cases: CI pipelines auto-approving when tests pass, webhook receivers auto-denying during incidents, external approval systems integrated via server-to-server calls.
- The `rd` CLI subcommand (`rd confirm ...`) is a natural future consumer; it is not shipped in this cycle but the API is designed to support it without changes.
- External dashboards, custom integrations, and third-party tooling can drive confirmation by calling the endpoints below directly.

Any feature that cannot be expressed through the API is a design bug — file it, don't work around it in the UI.

New controller: `com.dtolabs.rundeck.server.api.ApiConfirmController` (or colocated with existing execution API controllers; exact package TBD in Phase 3).

### POST `/api/{v}/execution/{id}/confirm`

**Authentication:** required. Session or API token.

**Authorization:** caller must have `confirm` ACL action on the execution resource. If the frozen `requiredConfirmerRoles` is non-empty, caller must hold at least one of those roles (ANY semantics).

**Request body:**

```json
{
  "decision": "approve",
  "comment": "LGTM after CI verified artifact hash"
}
```

**Preconditions (validated in order; first failure returned):**

1. Execution exists. Not found → 404.
2. Execution `status = 'waiting'`. Not waiting → 409 Conflict with message "execution is not pending confirmation".
3. Execution's `suspend_metadata.type == 'confirmation'`. Not a confirm suspension → 409 Conflict with message "execution is waiting for a different event type".
4. Caller authorization. Fails → 403 Forbidden.
5. Caller holds `requiredConfirmerRoles` if set. Fails → 403 Forbidden with message naming the required roles.
6. `decision` is a member of the frozen `decisionSet`. Fails → 400 Bad Request with message listing valid decisions.

**Actions on success (single DB transaction):**

1. Insert `ExecutionConfirmation` row with `confirmedBy=caller`, `confirmerRoles=<caller's roles>`, `decision`, `comment`, `confirmedAt=now()`, `timeout=false`, `stepContext=<suspended step context>`.
2. Construct `ConfirmationPayload` from the inserted row.
3. Call `ExecutionResumeService.markResumeReady(executionId, confirmationPayload)` in the same transaction. Per suspend/resume spec §8.1, `markResumeReady` itself checks for `status='waiting'` and returns false if state changed mid-transaction — if that happens, the inserted `ExecutionConfirmation` row is still valid (it records the attempted confirmation) but the response is 409.
4. Return 200 OK with `{"resumeReady": true, "confirmationId": <inserted-id>}`.

**Idempotency:** Not idempotent. A second POST after the first succeeded will see `status='running'` or terminal, and return 409. Two simultaneous POSTs for the same execution will be serialized at the DB level; one succeeds (201), the other gets 409.

### GET `/api/{v}/execution/{id}/confirm/status`

**Authorization:** `read` ACL action on the execution (not `confirm` — any reader can see pending confirmations exist).

**Response when execution is waiting for confirmation:**

```json
{
  "waiting": true,
  "message": "Approve production deploy of v1.42.3?",
  "decisionSet": ["approve", "deny"],
  "requiredConfirmerRoles": ["sre"],
  "waitStartedAt": "2026-04-14T15:00:00Z",
  "waitTimeoutAt": "2026-04-14T23:00:00Z",
  "callerCanConfirm": true
}
```

`callerCanConfirm`: true if the requesting user has `confirm` ACL and holds a required role (if set). Used by the UI to decide whether to enable the decision buttons.

**Response when execution is not waiting:** `{"waiting": false, "status": "<current-status>"}`.

### GET `/api/{v}/execution/{id}/confirmations`

**Authorization:** `read` ACL action on the execution.

**Response:** JSON array of `ExecutionConfirmation` rows for this execution, ordered by `confirmedAt` ASC.

Used for audit reporting and by the UI history view.

---

## 9. ACL action

New action: **`confirm`** on resource kind `execution` (project-scoped, matching existing `execution` actions `read`, `view`, `kill`, `delete`).

### Machinery to update

- `com.dtolabs.rundeck.core.authorization.AuthConstants` — add `ACTION_CONFIRM = "confirm"`.
- `com.dtolabs.rundeck.server.authorization.AuthService` (or wherever project execution actions are enumerated) — add to the action set.
- aclpolicy.yaml schema documentation — add `confirm` to the execution action enumeration.
- Default aclpolicy.yaml in the Rundeck distribution — add `confirm` to the `admin` role's allowed actions; do NOT add to lower roles (operators must opt users in explicitly).

### Example policy

```yaml
description: SRE role can run and confirm deploys in ops-deploy
context:
  project: ops-deploy
for:
  job:
    - equals:
        group: deploy
      allow: [run]
  execution:
    - equals:
        group: deploy
      allow: [read, view, confirm]
by:
  group: sre
```

### Authorization check points

| Check | Location |
|---|---|
| POST /confirm endpoint (can the caller confirm?) | `ApiConfirmController.confirm` |
| UI button enable/disable | `ApiConfirmController.status` returning `callerCanConfirm` |
| Direct service call (internal bypass) | Not permitted — even internal callers go through the controller path for uniform authorization. |

---

## 10. Notification event

New event type: **`execution.waiting-confirmation`**.

**Fired when:** `ExecutionService.onWorkflowSuspended` detects that the suspend metadata has `type = "confirmation"`, fires this notification event carrying the waiting-confirmation details. Fired on the node that persisted the suspension (the originating node at first suspend; potentially a different node if the step suspends again after an earlier resume).

**Fires:** once per suspension entry. Does NOT fire on resume, on timeout, or on terminal completion — those use existing event types (`execution.succeeded`, `execution.failed`, etc., which already handle resume-completion transparently).

**Payload:**

```json
{
  "eventType": "execution.waiting-confirmation",
  "executionId": 42,
  "jobId": "...",
  "jobName": "prod-deploy-v4",
  "project": "ops-deploy",
  "message": "Approve production deploy of v1.42.3?",
  "decisionSet": ["approve", "deny"],
  "requiredConfirmerRoles": ["sre"],
  "waitStartedAt": "2026-04-14T15:00:00Z",
  "waitTimeoutAt": "2026-04-14T23:00:00Z",
  "confirmUrl": "https://rundeck.prod/project/ops-deploy/execution/follow/42"
}
```

**Consumers:** Existing notification plugins (email, Slack, webhook) that already subscribe to execution events can subscribe to this event type via the job definition's `notification` section. Plugin authors do not need to be aware of confirmation semantics — the event is an opaque "something needs human action" signal.

**Not in scope:** authoring new notification plugins for confirmation UX. The event is the interface; delivery is existing surface.

---

## 11. Minimum UI affordance

### 11.1 Discovery flow (how confirmers find pending work)

Primary discovery is **notification-plugin-driven**. bob does not poll the Rundeck UI for pending confirmations; he is told about them via notification plugins (email, Slack, webhook) subscribed to the `execution.waiting-confirmation` event. Each notification carries a direct link to the execution detail page where the approve/deny affordance lives.

Secondary discovery via the execution list page: bob can navigate to `/project/{p}/executions`, filter by status = `waiting`, and click through. This is the backup path for when the notification plugin is misconfigured or bob needs to audit pending work retrospectively.

**Not in v1:** dashboard widget "my pending confirmations" across projects, header notification bell / inbox center, bulk confirmation UI, deep-link click-through authentication from email. All deferred.

### 11.2 Execution list page (minimal addition)

The existing execution list (`/project/{p}/executions`) needs two minimal changes:

1. The status filter dropdown includes `waiting` as a filterable state. Rows in `waiting` status render the raw status string.
2. Optionally, a small icon adjacent to the status string indicating the suspension type (`confirmation` glyph vs. `operator-pause` glyph per `operator-pause.md`). Implementation detail — acceptable if the list page just shows the status string without a type icon in v1.

No per-row approve/deny buttons on the list. Those live on the detail page only.

### 11.3 Execution detail page

Execution detail page (`/project/{p}/execution/follow/{id}`), when `status=waiting` AND `suspend_metadata.type == 'confirmation'`:

**Display:**
- The resolved `message` in a prominent banner.
- `waitStartedAt`, `waitTimeoutAt` as relative timestamps ("started 1h ago", "timeout in 7h").
- `requiredConfirmerRoles` if set ("requires role: sre").
- One button per decision in `decisionSet`. The first decision (success path) is styled as primary/green; others are neutral/red.
- A multi-line text field for `comment`.

**Behavior:**
- Button enable/disable driven by `GET /confirm/status → callerCanConfirm`.
- Buttons disabled with tooltip "you don't have permission to confirm" if false.
- Click → `POST /confirm` with `{decision, comment}`.
- On 200: page reloads; status shows `running` (or whatever the next state becomes).
- On 409/403/400: error banner with the server's message; form remains usable for retry.

**Existing execution history tab additions:**
- When an execution has related `ExecutionConfirmation` rows, show them in a new "Confirmations" sub-tab (or inline in the log viewer) with confirmer identity, decision, comment, timestamp. Read-only, no editing.

**Deferred:**
- Dashboard widget "executions waiting for your confirmation."
- Email/Slack click-through deep link that pre-authenticates.
- Inline confirmation from the log viewer itself.
- Bulk confirm across multiple executions.

All of these are future cycles.

---

## 12. Cluster mode semantics

Inherits fully from `workflow-suspend-resume.md §9`. No confirm-plugin-specific cluster behavior.

**Notes:**
- The `POST /confirm` endpoint may land on any node via the load balancer. The handling node writes to the shared DB; the resume worker (possibly on a different node) picks up the change on its next poll.
- Per suspend/resume spec decision (walkthrough gap G32 resolution): `execution.waiting-confirmation` fires from the node that **persisted the suspend**; terminal-completion events fire from the node that **completes the execution**. These may be different nodes. Operators should not treat "which node fired the notification" as a stable signal.
- The `ExecutionConfirmation` row is inserted on the node that handles the POST, not the node that resumes the execution. The audit trail accurately records who confirmed, regardless of which node subsequently resumed.

---

## 13. Test strategy

### Unit tests

- `ConfirmWorkflowStep` first-invocation path: constructs `SuspendRequest` with correct metadata; interpolates `${...}` against `DataContext`.
- `ConfirmWorkflowStep` resume-invocation path: every branch of §5 (approve, deny, timeout-deny, timeout-approve, timeout-fail).
- Config freeze: interpolation resolved once at suspend, frozen values read at resume, no re-interpolation.
- `ConfirmationPayload` Jackson round-trip with `type` discriminator.
- `ConfirmationPayload` Jackson subtype registration visible to `ResumePayload` polymorphic deserializer.

### Grails tests

- `ExecutionConfirmation` domain: constraints, immutability (updates rejected), multi-row per execution support.
- `ApiConfirmController.confirm`: happy path → 200 + row inserted + `markResumeReady` called. 409 on non-waiting. 409 on wrong suspension type. 403 on missing `confirm` ACL. 403 on missing required role. 400 on decision not in decisionSet.
- `ApiConfirmController.status`: waiting case, non-waiting case, callerCanConfirm true/false.
- `ApiConfirmController.confirmations`: returns rows in timestamp order, respects `read` ACL.
- ACL `confirm` action is recognized by the policy engine.
- `execution.waiting-confirmation` event fires on suspend with `type=confirmation` metadata, does NOT fire for non-confirmation suspensions, does NOT fire on resume.

### Integration tests

- **Full single-node approve flow:** start → build succeeds → confirm suspends → `POST /confirm approve` → deploy runs → success. Assert: one `ExecutionConfirmation` row with `decision=approve`, `timeout=false`. Execution ends `status=succeeded`.
- **Full single-node deny flow:** same path, POST with `decision=deny`. Assert: deploy step NEVER runs. `ExecutionConfirmation` row written with `decision=deny`. Execution ends `status=failed`.
- **Timeout-deny flow:** configure `timeout=5s`, `timeoutAction=deny`. Do not POST anything. Wait 6s. Assert: synthetic `ExecutionConfirmation` row with `confirmedBy=''`, `timeout=true`, `decision=''`. Execution ends `status=failed`.
- **Timeout-approve flow:** same but `timeoutAction=approve`. Assert: deploy runs, execution succeeds, synthetic confirmation row with `timeout=true`.
- **Two-node cluster:** start on `rundeck-a`, `POST /confirm` on `rundeck-b`, resume lands on either node. Assert: confirmation row exists; execution completes; audit row was written by whichever node received the POST.
- **Config freeze:** start execution → suspend → edit the job's `message` in the UI → `POST /confirm approve` → inspect the log and the stored frozen metadata. Assert: the confirmed `message` is the pre-edit value, NOT the edited value.
- **Unauthorized POST:** configure `requiredConfirmerRoles=sre`. POST as carol (no sre role). Assert: 403 Forbidden. Execution remains `waiting`. No `ExecutionConfirmation` row inserted.
- **Race: two simultaneous POSTs approve and deny:** exactly one wins, the other gets 409. Inspect the winning row's `decision`; verify it matches the one that returned 200.

---

## 14. Not in scope (v1)

- Multi-confirmer quorum (`minConfirmations > 1`).
- Custom decision sets beyond `approve,deny` at the plugin level (data model supports it; plugin does not).
- Free-text input, multi-choice, or picker prompts.
- Notification inbox UI (list of pending confirmations for the current user across all jobs).
- Deep-link click-through authentication from email/Slack notifications.
- Confirmation delegation (bob delegates his ability to confirm to carol).
- Confirmation expiry grace periods / reminders before timeout.
- Bulk confirm across multiple executions.
- Encrypted comment storage (comments are plaintext in the DB; sensitive data must not be placed in comments).
- Confirmation via CLI (`rd` subcommand for confirming executions).
- Approval chains (step A confirms before step B prompts).

Each belongs in a future cycle, not this one.

---

## 15. Open questions

1. **Comment in log stream.** Should the confirmer's comment be written to the execution's log stream as a log event (visible in live tail and log file) in addition to the `ExecutionConfirmation` audit row? Leaning yes for operator visibility; concerns: comments may contain sensitive information that shouldn't end up in widely-readable log files. Decision deferred to Phase 3.
2. **Decision as body field vs. path parameter.** `POST /confirm {"decision": "approve"}` (body) vs. `POST /confirm/approve` (path). Body is more extensible (works for any decision set, including free-text); path is more REST-conventional. Leaning body.
3. **Role match semantics.** `requiredConfirmerRoles=[sre, release-eng]`: does the confirmer need ANY role (OR, one of) or ALL roles (AND, all of)? Leaning ANY — "any one of these people can confirm" is the more common UX. Document explicitly.
4. **Schema location for subtype registration.** `ResumePayload` is in `core/`; `ConfirmationPayload` is in the confirm plugin module. Jackson polymorphic subtype registration needs to resolve at core deserialization time. Options: (a) register via ServiceLoader-discovered Jackson module from plugin module, (b) hardcode the built-in subtypes in core and document that third-party suspendable plugins need a core code change to register new payload types, (c) use a schema with `Map<String,Object>` and plugin-side downcast. Leaning (a) — it's the most extensible and keeps core free of plugin-specific knowledge. Verify feasibility during Phase 3.
5. **Timeout confirmer identity.** Synthetic timeout confirmations have `confirmedBy=''`. Alternative: a reserved pseudo-user `:timeout:` or `system:timeout`. Leaning empty string to avoid collision with real user IDs.

---

**⏸ PAUSE with `docs/specs/workflow-suspend-resume.md` amendments. Do not begin Phase 3 implementation until both specs are reviewed.**
