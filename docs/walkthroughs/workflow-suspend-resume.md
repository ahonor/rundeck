# walkthroughs/workflow-suspend-resume

**Purpose:** Pressure-test `docs/specs/workflow-suspend-resume.md` by tracing concrete scenarios end-to-end, marking every silence, contradiction, or broken composition as a gap with a proposed spec amendment. Per `spec-cycle.md`: "A walkthrough gap is a spec gap. Specs are not complete until walkthroughs close all gaps."

**Scenarios traced:**
1. [Normal path — abstract logical flow](#walkthrough-1--normal-path)
2. [Single-node deployment](#walkthrough-2--single-node-deployment)
3. [Two-node cluster, webhook on different node](#walkthrough-3--two-node-cluster-with-webhook-on-the-other-node)
4. [Cluster node crash mid-suspended-execution](#walkthrough-4--cluster-node-crash-mid-suspended-execution)

**Cross-cutting concern:** approve/deny confirmations as first-class plugins. Addressed inline in all walkthroughs since the confirm plugin is the concrete consumer under test.

**Gaps:** tagged `[GAP Gn: title]` inline, then consolidated in [§Gap summary](#gap-summary) and [§Spec amendments](#spec-amendments).

---

## Setup (shared across all walkthroughs)

### Environment

- **Rundeck cluster:** two nodes, `rundeck-a.prod` and `rundeck-b.prod`, behind a round-robin load balancer at `rundeck.prod`.
- **Shared Postgres** at `rundeck-db.prod:5432`.
- **Shared NFS** mounted at `/var/lib/rundeck/logs` on both nodes (NFSv4, `actimeo=0`).
- **Quartz** configured `jdbcStore = false` (RAM-only per node, the current default per `QuartzConfig.groovy:21`).
- **`ExecutionResumeService`** running on both nodes, polling every 2s.
- **Rundeck version** after this cycle ships (all waves landed).

### Project and job

- Project: `ops-deploy`
- Job: `prod-deploy-v4`, workflow:
  1. `build` — compile + package artifact (duration ~5 min)
  2. `confirm` — **built-in confirm step plugin** (new, this cycle), timeout 8h, timeoutAction `deny`, message "Approve production deploy of {artifactVersion}?"
  3. `deploy` — deploy artifact to prod fleet (duration ~15 min)
  4. `smoke-test` — post-deploy verification (duration ~3 min)

### Actors

- **alice** — release engineer, has `run` ACL on `ops-deploy` jobs. Initiates the deploy.
- **bob** — on-call SRE, has a new ACL action `approve` on `execution` resource in `ops-deploy` project. Approves the deploy.
- **carol** — read-only auditor, has `read` ACL on executions, NOT `approve`. Used to test authorization negatives.

### Built-in confirm step plugin (under design in this walkthrough)

New in this cycle, shipping as a first-class Rundeck built-in (not a third-party plugin):

- **Class:** `com.dtolabs.rundeck.plugin.confirm.ConfirmWorkflowStep` (tentative; pending spec §15).
- **Configuration fields:**
  - `message` (string): prompt shown to approvers
  - `timeout` (duration, e.g., `8h`): max wait before auto-action
  - `timeoutAction` (enum: `deny` | `approve` | `fail`): what to do on timeout
  - `requiredApproverRoles` (comma-separated role list, optional): if set, the approver must hold at least one of these roles IN ADDITION to the ACL `approve` action
  - `minApprovals` (integer, v1 = 1 always): future quorum support
- **Behavior (v1):** calls `context.suspend(SuspendRequest{...})` on first invocation; on resume, reads `ResumePayload`, logs approver identity, returns success (approved) or throws `StepException` (denied/timeout-fail).
- **Payload shape** (`ApprovalPayload extends ResumePayload`): `{approved: bool, approver: string, approverRoles: List<string>, comment: string, approvedAt: Instant, timeout: bool}`.

---

## Walkthrough 1 — Normal path

**Scenario:** Abstract logical trace. Not specific to single vs. cluster. Finds structural gaps that apply regardless of deployment topology.

**Actors:** alice initiates, bob approves. Cluster topology unspecified.

**Timeline:** T=0 deploy starts. T=5m confirm step begins. T=1h bob approves. T=1h10s execution completes.

### Trace

| # | T | Event | Handler | Spec cite | Gap |
|---|---|---|---|---|---|
| 1 | 0 | alice clicks "Run" on `prod-deploy-v4` in the Rundeck UI | `ExecutionController.runJobNow` → `ExecutionService.executeJob` → `ExecutionService.executeAsyncBegin` | Unchanged (existing Rundeck) | — |
| 2 | 0 | Execution row created with `status='running'`, `dateStarted=T0`, `serverNodeUUID=<some node>` | `ExecutionService.executeAsyncBegin` | Unchanged | — |
| 3 | 0 | Log writer opened, header written | `LogFileStorageService.getLogFileWriterForExecution:694` → `FSStreamingLogWriter` opens, calls `formatter.outputBegin()` | Spec §5.2: "header is written exactly once per execution, at the `getLogFileWriterForExecution` path" | — |
| 4 | 0 | Thread spawned, engine enters `executeWorkflowImpl` | `WorkflowExecutionServiceThread.run` → `EngineWorkflowExecutor.executeWorkflowImpl` | Unchanged | — |
| 5 | 0 | Step 1 (`build`) begins | Normal step execution | Unchanged | — |
| 6 | 5m | Step 1 succeeds, data context updated with `artifactVersion=1.42.3` | Step result merged via `WFSharedContext` | Unchanged | — |
| 7 | 5m | Step 2 (`confirm`) begins. Engine invokes the `ConfirmWorkflowStep.executeWorkflowStep(context, item)` | Engine step dispatch | Spec §5.1 canonical pattern | — |
| 8 | 5m | Confirm plugin checks `context.getResumePayload()` → null (first invocation) | Plugin code | Spec §5.1 contract clause "called exactly twice" | **[GAP G1: `SuspendRequest.payload` shape for approval events]** Spec §2.1 says `SuspendRequest.payload` is `Map<String,Object>`, but the confirm plugin needs to communicate *structured metadata* to downstream consumers (UI, approval API, notification system): the approval message, required roles, timeout action. Is that in `payload`? Or is there a separate "suspend metadata" field visible to non-plugin consumers? |
| 9 | 5m | Confirm plugin constructs `SuspendRequest.builder().reason("awaiting approval").waitingFor("user-approval").timeoutMs(TimeUnit.HOURS.toMillis(8)).payload(Map.of("message", "...", "requiredApproverRoles", ["sre"], "timeoutAction", "deny")).build()` | Plugin code | Spec §5.1 | — (resolved by G1) |
| 10 | 5m | Plugin calls `context.suspend(request)` | `SuspendableExecutionContext.suspend` | Spec §5.1 default-method addition | — |
| 11 | 5m | `SuspensionPolicy.validate(context)` invoked lazily inside `suspend()` | Spec §12 locked decision 8 | Spec §4 I7/I8 | **[GAP G2: Policy sees the full context but the confirm plugin has a `requiredApproverRoles` the policy should NOT validate]** Clear separation needed between "is this workflow suspendable at all" (policy concern) vs. "who can approve this specific suspension" (confirm plugin concern). Spec §4 invariants are workflow-level; approver authorization is plugin-level. The spec needs to declare this split explicitly. |
| 12 | 5m | Policy passes. `context.suspend()` returns a `SuspendedStepResult` carrying the request | Spec §2.1 | Spec §5.1 | — |
| 13 | 5m | Confirm plugin's `executeWorkflowStep` returns `return context.suspend(request)` | Plugin return | Spec §5.1 canonical pattern | — |
| 14 | 5m | Engine's `WorkflowEngineOperationsProcessor` observes `SuspendedStepResult` on the state change queue | Spec §6.1 step 1 | Spec §6.1 | — |
| 15 | 5m | Processor sets `suspendRequested` flag, drains already-completed operations (none, sequential), exits loop | Spec §6.1 | Spec §6.1 | — |
| 16 | 5m | `WorkflowEngine.processOperations` returns with suspended state on envelope | Spec §6.1 step 2 | Spec §6.1 | **[GAP G3: shape of the return envelope is unspecified]** Spec §6.1 says "returns a result set with the suspend flagged on the return envelope" but doesn't name the envelope type. Is it a new class, a modified existing class, or an additional return? Implementation can't proceed without this being nailed down. |
| 17 | 5m | `EngineWorkflowExecutor.executeWorkflowImpl` detects suspended state during result aggregation | Spec §6.1 step 3 | Spec §6.1 | — |
| 18 | 5m | Executor builds `WorkflowExecutionResult` with `isSuspended=true`, `workflowSuccess=false`, suspended steps NOT in `stepFailures` | Spec §6.1 step 3 | Spec §6.1 | — |
| 19 | 5m | `BaseWorkflowExecutor` detects suspended result and suppresses workflow-end event firing | Spec §6.1 step 4 | Spec §6.1 | **[GAP G4: which specific events are "workflow-end" events?]** `WorkflowExecutionListener` has several callbacks: `beginWorkflowExecution`, `finishWorkflowExecution`, `beginExecuteNodeStep`, etc. On suspend, `finishWorkflowExecution` must NOT fire; but what about any in-progress step-end listener calls for the suspended step itself? The step didn't "finish" in the normal sense. The spec says nothing about individual listener calls — only "workflow-end events." |
| 20 | 5m | `WorkflowExecutionServiceThread` stores suspended result, exits `run()` | Spec §6.1 step 5 | Spec §6.1 | — |
| 21 | 5m | **Log flush + suspend-close happen BEFORE the thread's run() returns, per I5 ordering** | **[GAP G5: who calls `writer.suspend()`?]** Spec §4 I5 says the order is flush → suspend-close writer → DB commit. But which component actually calls `CheckpointableStreamingLogWriter.suspend()`? Is it the executor thread (inside `executeWorkflowImpl`), the service thread's `run()` epilogue, or `ExecutionService.onWorkflowSuspended`? Spec §6.1 and §8.4 are both silent on this. | — |
| 22 | 5m | Quartz join loop in `ExecutionJob.executeCommand` observes `thread.isAlive() == false`, exits loop | `ExecutionJob.groovy:~517` | Spec §8.4 | — |
| 23 | 5m | `ExecutionJob` checks `thread.isSuspended()` → true, routes to `executionService.onWorkflowSuspended(execmap, thread.result)` | Spec §8.4 | Spec §8.4 | — |
| 24 | 5m | `ExecutionService.onWorkflowSuspended`: serializes checkpoint, begins DB transaction, updates row | Spec §8.2 + I5 | Spec §4 I5 | **[GAP G6: `onWorkflowSuspended` is called, but who owns the log flush/close?]** This is the same as G5 from the other direction. If `onWorkflowSuspended` does the log close, then I5 step 1 (flush log) and step 2 (suspend-close writer) happen inside `onWorkflowSuspended` before it starts the DB transaction (step 3). But spec §8.2 describes `onWorkflowSuspended` as a pure DB operation. Needs explicit spec text. |
| 25 | 5m | Checkpoint blob serialized via Jackson: context data subset + completed step results + suspend request | Spec §7.3 | Spec §7.3 | **[GAP G7: checkpoint must include the step's OWN data, not just prior steps]** Spec §7.3's `completedStepResults` tracks steps 0..N-1 but the suspended step N has its own state too: the `SuspendRequest` it constructed. Spec §7.3 shows `suspendRequest` at the top level of the blob, but that's fine. However — the plugin might have captured local state during the pre-suspend portion of its `execute` method (e.g., it read a job option, queried the database, computed a hash). v1 says "plugins must encode cross-suspend state in `SuspendRequest.payload`." Is this documented clearly enough in §5.1? |
| 26 | 5m | DB row updated: `status='waiting'`, `serverNodeUUID=NULL`, `wait_started_at=T5m`, `wait_timeout_at=T5m+8h`, `resume_ready=false`, `resume_payload=null`, `checkpoint_data=<blob>` | Spec §2.3, §7.1 | Spec §7.1 | — |
| 27 | 5m | DB transaction commits. Log file on disk: footerless, contains header + build step events. **Log file position and write handle state on disk?** | Spec §4 I5 | Spec §5.2 | **[GAP G8: append-mode open after a flush-and-close — are there any file descriptor leaks?]** The writer's `suspend()` closes the `FileOutputStream`. On resume (§5.2 `openForResume`), a new `FileOutputStream(file, true)` opens. Spec says this is safe, but does not explicitly state the invariant "no file descriptor is held across the suspend window." Worth declaring explicitly. |
| 28 | 5m | `ExecutionJob` returns a `RunResult` flagged suspended | Spec §8.4 | Spec §8.4 | — |
| 29 | 5m | `saveState()` is called with the suspended result, early-returns without setting `dateCompleted` or firing notifications | Spec §8.4 | Spec §8.4 | **[GAP G9: does `saveState` receive the `RunResult` or just the execmap?]** Looking at existing `ExecutionJob.saveState` signature — needs verification. The current signature uses fields pulled from `execmap`, not a `RunResult`. The spec's early-return requires the suspended flag to be accessible inside `saveState`. Implementation gap, not a design gap, but worth confirming. |
| 30 | 5m | Quartz thread released. Thread pool slot freed. | — | — | — |
| 31 | 5m | **WHAT FIRES A NOTIFICATION** to bob that approval is needed? | **[GAP G10: the spec is silent on "who tells bob"]** Spec §13 lists "notification integration" as not-in-scope, but "first-class confirm plugin" implies SOME notification path. At minimum: the confirm plugin needs to emit an event that existing notification plugins can subscribe to. Or the UI needs to poll. Or the API provides a "list pending approvals" endpoint that an external dashboard polls. This is a **significant missing design element.** | — |
| 32 | 1h | bob notices (via whatever mechanism G10 resolves). bob clicks "Approve" in the Rundeck UI with comment "LGTM after CI passed" | **[GAP G11: UI is out of scope in spec §13, but "first-class confirm plugin" implies a minimum UI]** What does bob see when the execution is `waiting`? Spec §13 defers UI polish but G11 asks: does the v1 UI show ANY approve/deny affordance, or does bob need to hit the API directly via curl? | — | — |
| 33 | 1h | UI calls `POST /api/{v}/execution/42/approve` with body `{"comment": "LGTM after CI passed"}` | New endpoint | **[GAP G12: spec does not define the approval API]** Spec §8.1 mentions `ExecutionResumeService.markResumeReady(execId, payload)` as "called by webhook plugins / API endpoints" but does not define the specific endpoint the built-in confirm plugin expects. Needs a new API controller: `ApprovalController` with routes `approve`, `deny`, `status`. | — |
| 34 | 1h | API endpoint authorizes bob against ACL action `approve` on execution 42 | **[GAP G13: new ACL action]** Does `approve` exist as an ACL action? Existing actions on `execution` resource are `read`, `view`, `kill`, `delete`. Need to add `approve` as a new action. ACL policy syntax, aclpolicy.yaml schema, authz machinery all need the new action recognized. | — | — |
| 35 | 1h | API checks `requiredApproverRoles` from the suspend metadata: bob holds role `sre` ✓ | **[GAP G14: where does the API read `requiredApproverRoles` from?]** From the checkpoint blob? From `SuspendRequest.payload`? From the job definition (cached)? The API endpoint runs on whichever node received the HTTP request, which may differ from the node that started the execution. Needs a defined read path. | — | — |
| 36 | 1h | API constructs `ApprovalPayload{approved: true, approver: "bob", approverRoles: ["sre"], comment: "LGTM...", approvedAt: now(), timeout: false}` | — | — | **[GAP G15: `ApprovalPayload` is a `ResumePayload` subtype]** Spec §2.1 defines `ResumePayload` but does not describe polymorphism or subtypes. If the confirm plugin is first-class, `ApprovalPayload` is a known shape the plugin can downcast to. Either (a) `ResumePayload` becomes an interface with known subtypes, (b) it's a `Map<String, Object>` everyone agrees to keys for, or (c) it's a `String type` discriminator + `Map payload`. Decision needed. |
| 37 | 1h | API calls `ExecutionResumeService.markResumeReady(42, approvalPayload)` | Spec §8.1 | Spec §8.1 | — |
| 38 | 1h | `markResumeReady` opens a DB transaction, updates `resume_ready=true`, `resume_payload=<json>`, commits | Spec §8.1 | Spec §8.1 | **[GAP G16: what if the execution is no longer waiting?]** Between bob clicking approve and the DB update, the execution might have been aborted by alice, or its timeout might have expired and the timeout sweep already marked it `failed`. `markResumeReady` must check `status='waiting'` in its WHERE clause and return false if not (so the API can return 409 Conflict). Spec §8.1 method signature shows a `boolean` return; needs semantics. |
| 39 | 1h | API returns 200 OK to bob's UI. UI shows "Approved, resuming..." | — | — | — |
| 40 | 1h 0s-2s | `ExecutionResumeService.scheduledResumePoll` on some node ticks, queries `status='waiting' AND resume_ready=true AND serverNodeUUID IS NULL` | Spec §8.1, §9.3 | Spec §9.3 | — |
| 41 | 1h 0s-2s | Worker finds execution 42, attempts atomic claim `UPDATE execution SET serverNodeUUID=<self>, status='running' WHERE id=42 AND status='waiting' AND serverNodeUUID IS NULL` | Spec §6 I2, §9.3 | Spec §4 I2 | **[GAP G17: what about `dateStarted` during resume?]** The claim UPDATE sets `status='running'` but the spec says `dateStarted` is set exactly once (I1). On claim, is `dateStarted` touched? Per invariant, no. But does the UI / reaper / metrics code handle a `status='running'` row whose `dateStarted` is from an hour ago correctly? The audit's reaper analysis said `findRunningExecutions` uses `dateStarted < before` as the orphan predicate. If the orphan threshold is, say, 5 minutes, then a newly resumed execution with `dateStarted=1h ago` would be **immediately flagged as an orphan** by the next reaper tick, even though it's genuinely running now. |
| 42 | 1h 0s-2s | Claim succeeds (1 row affected). Worker calls `resumeExecution(execution)` | Spec §8.1, §9.3 | Spec §9.3 | — |
| 43 | 1h 0s-2s | `resumeExecution`: reads `checkpoint_data`, parses via Jackson | Spec §7.3, §8.1 | Spec §7.3 | — |
| 44 | 1h 0s-2s | `ExecutionService.buildExecutionContextFromCheckpoint(exec, checkpoint)` constructs a new `ExecutionContextImpl` | Spec §8.2 | Spec §8.2 | **[GAP G18: the new listener chain for the resumed execution]** The rehydrated context needs `executionListener`, `workflowExecutionListener`, `loggingManager`, `executionLogger` rebuilt. On resume, these are NEW listener instances. Do any existing listener contracts assume "begin/end are called on the same instance in the same thread"? If so, the suspended step's pre-suspend lifecycle calls happened on listener A, and the post-resume lifecycle calls happen on listener B. Listener implementations that expect in-memory state to be preserved across `begin`/`end` pairs will break. |
| 45 | 1h 0s-2s | Log writer reopened in resume mode via `LogFileStorageService.openForResume(exec)` — `FileOutputStream(file, true)` + writer with `resumeMode=true` flag | Spec §5.2 | Spec §5.2 | — |
| 46 | 1h 0s-2s | New `WorkflowExecutionServiceThread` spawned with rehydrated context + resume payload | Spec §6.2 | Spec §6.2 | **[GAP G19: resume thread's Grails context/security/transaction setup]** Spec §14 open question 4 flagged this. If `ExecutionJob`'s original path sets up Grails transaction scope, security context, MDC, etc., the resume worker spawning a thread directly from a Spring-scheduled bean does NOT get the same setup. Needs either factoring out (spec §12 decision 10) or the resume worker explicitly replicates the setup. Implementation blocker. |
| 47 | 1h 0s-2s | Thread enters `EngineWorkflowExecutor.executeWorkflowResume(context, item, checkpoint, resumePayload)` | Spec §6.2 | Spec §6.2 | — |
| 48 | 1h 0s-2s | Executor primes `MutableStateObj` from checkpoint's completed step results (step 0 `build` = success), marks current step index = 1 (`confirm`) | Spec §6.2 | Spec §6.2 | **[GAP G20: completed-step-result serialization contract]** Step results can hold arbitrary data (`Map<String,Object>`, node results, etc.). Are these Jackson-serializable? Spec §7.3 shows the shape as `{stepIndex, success, data}` but doesn't enumerate what `data` can contain. A step result holding a live `INodeEntry` or a plugin-specific object will fail. Needs a documented contract: step result data must be Jackson-serializable OR get flattened to primitives before checkpoint. |
| 49 | 1h 0s-2s | Executor re-invokes the confirm step's `executeWorkflowStep(context, item)` | Spec §6.2 | Spec §6.2 | **[GAP G21: fresh plugin instance or cached?]** Is the confirm step plugin instance the same Java object as the pre-suspend invocation, or a fresh one? Spec §5.1 contract says "step instance is NOT preserved." But the walkthrough crosses a node boundary in the cluster scenario; even same-node resume is a different thread and potentially a different plugin loader state. Fresh is safer; spec should state it. |
| 50 | 1h 0s-2s | Confirm plugin checks `context.getResumePayload()` → non-null, downcast to `ApprovalPayload` | Plugin code | Spec §5.1 + G15 | — (pending G15) |
| 51 | 1h 0s-2s | Plugin inspects payload: `approved=true`, `approver=bob`, `comment="LGTM..."` | Plugin | — | **[GAP G22: approval audit trail]** The spec does not define WHERE the approval event is recorded permanently. Options: (a) the log stream (as a special log event), (b) a new `ExecutionApproval` grails domain table, (c) the existing activity log / notification log. Without this, there is no lasting audit trail of "who approved this deploy and when." Compliance concern. |
| 52 | 1h 0s-2s | Plugin emits a log line "approved by bob: LGTM after CI passed" via the normal log writer | Plugin → `context.getExecutionListener().log(...)` | Spec §5.2 | — |
| 53 | 1h 0s-2s | Plugin returns `StepExecutionResultImpl.success()` | Spec §5.1 | Spec §5.1 | — |
| 54 | 1h 0s-2s | Engine continues to step 2 (`deploy`) | Normal engine path | Unchanged | — |
| 55 | 1h 15m | Step 2 `deploy` succeeds after 15 minutes of normal execution | — | — | — |
| 56 | 1h 18m | Step 3 `smoke-test` runs for 3 minutes, succeeds | — | — | — |
| 57 | 1h 18m | Workflow completes normally. `WorkflowExecutionResult` has `workflowSuccess=true` | — | — | — |
| 58 | 1h 18m | Log writer's normal `close()` writes `^END^` footer | Spec §5.2 | Spec §5.2 | — |
| 59 | 1h 18m | Terminal write path: `dateCompleted=T1h18m`, `status='succeeded'`, `checkpoint_data=null` (cleared) | Spec §4 I4 | Spec §4 I4 | **[GAP G23: clearing checkpoint on success]** Spec §2.3 says `checkpoint_data` is "populated only when status='waiting'" but does not state that successful completion clears it. Should be explicit: on transition from `waiting` → `running` → terminal, the checkpoint is cleared during the terminal write. Otherwise old checkpoint data lingers in terminal rows — confusing and wasteful. |
| 60 | 1h 18m | Completion notifications fire exactly once | Spec §4 I4 | Spec §4 I4 | — |

### What Walkthrough 1 proves

- The logical flow is coherent end-to-end.
- Invariants I1, I4, I5, I6 hold when traced step-by-step.
- The suspend/resume primitive composes with the canonical confirm-plugin pattern.

### What Walkthrough 1 found

**23 gaps.** The spec is missing substantial surface area around the first-class confirm plugin (G10–G16, G22), the listener / thread-context model (G4, G18, G19), and serialization contracts (G7, G20). A few are implementation-detail clarifications (G5, G6, G9), but most are real design omissions.

---

## Walkthrough 2 — Single-node deployment

**Scenario:** `rundeck-b` is offline / removed from the deployment. Only `rundeck-a` is running. Everything happens on `rundeck-a`. This walkthrough verifies nothing in the spec implicitly assumes the cluster case is default.

**Actors:** alice initiates, bob approves.

**Timeline:** Same as Walkthrough 1.

### Delta from Walkthrough 1

Only the steps that differ:

| # | T | Event | Delta | Gap |
|---|---|---|---|---|
| 2 | 0 | Execution row's `serverNodeUUID = <rundeck-a's UUID>` | No alternative to pick | — |
| 31 | 5m | bob notification fires via `rundeck-a`'s notification subsystem | Same path as cluster | — |
| 33 | 1h | bob's `POST /approve` lands on `rundeck-a` (only node) | — | **[GAP G24: single-node mode does not disable the cluster claim UPDATE]** The atomic claim UPDATE still requires `serverNodeUUID IS NULL`. In single-node mode this still works, but the UPDATE is now contending with itself (same node both writes NULL at suspend and claims at resume). No actual contention because it's a single worker, but the machinery is designed for multi-node and adds overhead on single-node. Design question: do we short-circuit in single-node mode? Leaning **no** — consistency is more valuable than micro-optimization. Worth noting in spec as an explicit decision. |
| 40 | 1h 0s-2s | `ExecutionResumeService` on `rundeck-a` (the only node) polls, finds, claims | Same as cluster | — |
| 42 | 1h 0s-2s | Claim wins trivially (no other node to race) | — | — |
| 46 | 1h 0s-2s | New `WorkflowExecutionServiceThread` spawned on `rundeck-a` | Same JVM as original | **[GAP G25: same-JVM resume — static state, caches, in-memory data]** Since it's the same JVM, some state that would be "lost" in the cluster case is actually still present: plugin loader caches, static fields, in-memory configuration. The spec's contract ("component instance is not preserved") still holds, but plugins that rely on JVM-wide mutable static state would "get lucky" on single-node and "break" on cluster. This is a **latent portability trap**: developers test on single-node, it works, they ship, then cluster deployments break. Spec should require tests to run in a cluster-simulating mode that forces fresh context rehydration. |

### What Walkthrough 2 proves

- Single-node deployment is a degenerate case of the cluster mechanism — no separate code path required.
- No gap in the primitive's design; all gaps found are spec clarifications.

### What Walkthrough 2 found

**2 gaps.** Both are about making the cluster-vs-single-node parity explicit and preventing the single-node "lucky passing" trap.

---

## Walkthrough 3 — Two-node cluster with webhook on the other node

**Scenario:** alice initiates deploy from `rundeck-a`. Execution runs on `rundeck-a`, suspends. bob's approval lands on `rundeck-b` (load balancer routed him there). `rundeck-b` resumes and completes the deploy.

**Actors:** alice on `rundeck-a`, bob's browser → LB → `rundeck-b`.

### Delta from Walkthrough 1

| # | T | Event | Delta | Gap |
|---|---|---|---|---|
| 1 | 0 | alice's click routed to `rundeck-a` | — | — |
| 2 | 0 | Execution row's `serverNodeUUID = <rundeck-a's UUID>` | — | — |
| 27 | 5m | DB transaction commits. Log file footerless on shared NFS. | — | **[GAP G26: NFS close-to-open consistency]** Spec §9.4 mentions NFSv4 and `actimeo=0`/`noac` but does not explicitly require close-to-open consistency semantics. NFSv4 provides CTO by default; NFSv3 requires specific mount options. The spec should declare "shared FS must provide close-to-open consistency." Otherwise rundeck-b could see a stale file size when opening for append. |
| 33 | 1h | bob's `POST /approve` routed to `rundeck-b` by LB | `rundeck-b`'s `ApprovalController` handles it | **[GAP G27: authorization on rundeck-b needs the job definition visible]** The approval controller on `rundeck-b` needs to authorize bob against the job's approver roles. Does `rundeck-b` have the job definition? Yes — jobs are shared state in the DB. But does the local `rundeck-b` process have it cached? Any cache invalidation needed? Probably not — normal Grails domain read gets the fresh row. Worth confirming in spec. |
| 35 | 1h | `rundeck-b` reads `requiredApproverRoles` — from the checkpoint on the execution row (NOT from the job definition, which may have changed since execution start) | **[GAP G28: reading suspend metadata from checkpoint vs. job definition]** The spec says the suspend metadata is in the checkpoint. But the API controller reading it needs to parse the checkpoint JSON to extract the approver roles — coupling the API layer to the checkpoint blob format. Cleaner: stash suspend metadata in its own DB column (e.g., `suspend_metadata` JSON) separate from the opaque `checkpoint_data`. OR add a `ExecutionResumeService.getSuspendMetadata(execId)` method that encapsulates the read. | — |
| 37 | 1h | `rundeck-b` calls `markResumeReady(42, approvalPayload)` | Same service method, on `rundeck-b`'s instance | — |
| 40 | 1h 0s-2s | **TWO** resume workers are polling: `rundeck-a`'s and `rundeck-b`'s | Race | — |
| 41 | 1h 0s-2s | Both workers attempt atomic claim | Race | — |
| 42 | 1h 0s-2s | Exactly one wins (DB serializability). Say `rundeck-b` wins. `rundeck-a`'s claim returns 0 rows, it logs and skips. | Spec §4 I2 | Spec §4 I2 holds | — |
| 45 | 1h 0s-2s | `rundeck-b` opens log file in resume mode. **NFS read-your-writes** must guarantee rundeck-a's flushed writes are visible. | Spec §9.4 | Spec §9.4 (implied) | — (resolved by G26) |
| 46 | 1h 0s-2s | New thread on `rundeck-b`. **Different JVM** from original. | Distinct from Walkthrough 2 | **[GAP G29: plugin loader visibility on `rundeck-b`]** The confirm plugin class must be loaded on `rundeck-b` as well as `rundeck-a`. In a uniform cluster deployment this is given. But what if the two nodes have different plugin sets (operator error, rolling upgrade)? On resume, if `rundeck-b` cannot load the step's plugin class, what happens? Spec §10.2 has "context component class not loadable" but does NOT cover "step plugin class not loadable." Needs symmetry. |
| 48 | 1h 0s-2s | `MutableStateObj` primed from checkpoint | Same as Walkthrough 1 | — |
| 49 | 1h 0s-2s | Step is re-invoked on `rundeck-b`'s plugin instance | New instance, new loader (possibly) | **[GAP G30: plugin configuration drift]** If between suspend and resume the job definition is EDITED on `rundeck-b` (alice updates the message text for the confirm step), which version does the resumed step see — the version at suspend time or the current version? Checkpoint captures the suspend-time snapshot; but the plugin instance on resume reads its configuration from the current job definition. This is a **real divergence**: plugin instance behavior can differ from its pre-suspend self. Decision needed: freeze configuration at suspend time (stash in checkpoint) or re-read at resume time. Freezing is safer and more auditable but complicates the spec. |
| 53 | 1h 0s-2s | Plugin returns success | — | — |
| 55-57 | — | Deploy and smoke-test run on `rundeck-b` | — | — |
| 59 | 1h 18m | Terminal write happens on `rundeck-b`. `ExecutionResumeService.resumeExecution` must invoke the terminal write path that `ExecutionJob` normally invokes. | Spec §12 decision 10 | Spec §12 decision 10 | **[GAP G31: terminal write path from outside Quartz]** Spec §12 decision 10 says "factor out if needed." Walkthrough 3 proves it is needed: the resume worker on `rundeck-b` must drive the `saveState` equivalent, completion notifications, post-completion hooks, and log footer write. If any of those are Quartz-coupled by reading from `Trigger.getJobDetail()` or similar, they break here. Must be verified during Wave 2 implementation. |
| 60 | 1h 18m | Completion notifications fire from `rundeck-b` | — | **[GAP G32: notification delivery node]** Do notifications fire from the node that completed the execution (`rundeck-b`) or the node that originated it (`rundeck-a`)? The existing Rundeck notification system fires from wherever the Quartz job runs. Here the resume worker runs on `rundeck-b`, so notifications come from `rundeck-b`. Consumers of notifications that care about "which node" will see a mix. Document this as expected behavior; not a bug but a surprise. |

### What Walkthrough 3 proves

- Cross-node handoff works via the atomic claim + shared FS.
- The single-writer invariant holds.
- Notification and completion write paths execute on the resuming node, not the originating node.

### What Walkthrough 3 found

**7 gaps.** The two most significant:
- **G30: plugin configuration drift across suspend/resume.** Does the plugin see the config it had at suspend time or the current config? v1 design decision required.
- **G31: terminal-write factoring is MANDATORY, not optional.** Spec §12 decision 10 hedged "if needed" — walkthrough proves it IS needed because `rundeck-b`'s resume worker is NOT inside a Quartz execution.

---

## Walkthrough 4 — Cluster node crash mid-suspended execution

**Scenario:** Deploy starts on `rundeck-a`. Step 2 (confirm) suspends at T=5m. `rundeck-a` crashes at T=30m (OS kernel panic, `kill -9` on the JVM, power failure — any hard kill with no graceful shutdown). bob's approval arrives at T=1h, routed to `rundeck-b` (only live node).

### Trace

| # | T | Event | Handler | Spec cite | Gap |
|---|---|---|---|---|---|
| 1-30 | 0-5m | Identical to Walkthrough 1 through "Quartz thread released on `rundeck-a`" | — | — | — |
| 31 | 5m | Execution row state: `status='waiting'`, `serverNodeUUID=NULL`, `checkpoint_data=<blob>`, `resume_ready=false`, log file footerless on shared NFS | DB + NFS | Spec §4 I5 | — |
| 32 | 30m | `rundeck-a` crashes. JVM process dies. All in-memory state on `rundeck-a` lost. | — | — | — |
| 33 | 30m | **Impact on running executions on `rundeck-a`:** any execution with `status='running'` AND `serverNodeUUID=rundeck-a` is orphaned | Pre-existing Rundeck behavior | Unchanged | — |
| 34 | 30m | **Impact on our waiting execution:** its `serverNodeUUID` is NULL, `status='waiting'`. The crash did not touch the DB row. | — | Spec §4 I2, §9.1 | — |
| 35 | 30m | **Impact on log file:** the writer on `rundeck-a` was already closed (per I5 step 2 at T=5m). No data loss, no open file handle leaked. | — | Spec §4 I5 | — |
| 36 | 30m onwards | `rundeck-b`'s reaper runs | `ExecutionService.cleanupRunningJobs` | Pre-existing + spec §8.3 criteria exclusion | **[GAP G33: reaper targets `rundeck-a`'s UUID specifically?]** `findRunningExecutions(serverUUID)` finds running executions for a specific dead server. How does `rundeck-b` know `rundeck-a` is dead and should be cleaned up? Existing Rundeck behavior: on startup, a node cleans up its OWN stale executions. There is no cross-node "hey, rundeck-a hasn't checked in, clean its jobs" mechanism (no heartbeat table, per audit §2.5). So `rundeck-a`'s running executions (non-suspended) will NOT be cleaned up by `rundeck-b`. They will be cleaned up by `rundeck-a` itself when it restarts. **This is a pre-existing Rundeck behavior, not a suspend/resume gap — but the spec should acknowledge it so operators understand that crash-recovery of in-flight non-suspended executions is unchanged.** |
| 37 | 30m onwards | `rundeck-b`'s reaper queries with `status='waiting'` exclusion, so it DOES NOT touch our waiting execution. | Spec §4 I1 (reaper exclusion) + audit §2.5 | Spec §8.3 | — |
| 38 | 1h | bob's `POST /approve` routed to `rundeck-b` (only live node) | — | — | — |
| 39 | 1h | `rundeck-b` authorizes, writes `resume_ready=true`, `resume_payload=<...>` | Spec §8.1 | Spec §8.1 | — |
| 40 | 1h 0s-2s | `rundeck-b`'s resume worker polls, finds execution, claims | — | — | — |
| 41 | 1h 0s-2s | Claim succeeds (no competing worker — `rundeck-a` is dead) | Spec §4 I2 | Spec §4 I2 | — |
| 42 | 1h 0s-2s | `rundeck-b` rehydrates context, opens log in resume mode, spawns thread | Spec §8.1, §6.2 | Spec §6.2 | — |
| 43 | 1h 0s-2s | Resume proceeds exactly as Walkthrough 3 from here | — | — | — |
| 44-50 | — | Deploy and smoke-test run on `rundeck-b`. Completion on `rundeck-b`. | — | — | — |

### Second crash scenario variant: `rundeck-a` crashes DURING the suspend commit

**Timeline:** `rundeck-a` crashes at exactly T=5m, during the suspend DB transaction.

| Sub-case | When `rundeck-a` crashes | Outcome | Gap |
|---|---|---|---|
| 4a | Before log flush (I5 step 1) | Log buffer lost. Execution still `status='running'`, `dateCompleted=null`. Eventually reaped by `rundeck-a` on restart OR never if `rundeck-a` doesn't restart — needs manual intervention. Pre-existing behavior. | None new — but worth documenting |
| 4b | After log flush, before suspend-close (I5 step 2) | Log data on disk. Writer may or may not have `^END^` — if the crash was mid-close(), the footer may be partially written. But FSStreamingLogWriter's suspend() intentionally does NOT call outputFinish(), so footer is never written in the suspend path. Execution still `running`, reaped normally. | **[GAP G34: partial close on crash]** If `suspend()` is mid-flush when the JVM dies, is the file in a valid state? Linux buffered I/O means the `flush()` call flushes to kernel buffers, but kernel buffers may not be fsync'd to disk. On crash, kernel buffers may be lost. If the underlying filesystem is NFS, fsync behavior depends on mount options. The spec should say: suspend() MUST fsync before returning, AND the implementation must verify FSStreamingLogWriter (via FileOutputStream) either already fsyncs on flush+close or adds an explicit fsync. |
| 4c | After suspend-close, before DB transaction begin (I5 step 3) | Writer closed, log clean, but execution still `status='running'` in the DB. Execution reaped normally (marked `incomplete`). Log file has valid content up to pre-suspend flush, no footer, looks like an in-progress execution log. Operator sees an `incomplete` row with unusually long log — acceptable. | — |
| 4d | After DB update, before commit (I5 step 4-5) | Transaction rolls back on crash. Same state as 4c. | — |
| 4e | After commit (I5 step 6), before thread return | Execution is `status='waiting'`, `serverNodeUUID=NULL` in DB. Thread about to return but doesn't get to. **Quartz job on `rundeck-a` never gets `RunResult`**. But Quartz thread is also dead. No effective difference — the execution is persisted as waiting, which is the intended outcome. `rundeck-b` picks it up on resume. | — |
| 4f | During `rundeck-a`'s `ExecutionJob.saveState` early-return for suspended | Thread already set DB state; `saveState` was about to early-return anyway. No data loss. Execution is `waiting`. `rundeck-b` picks it up. | — |

### Third crash scenario variant: `rundeck-b` crashes during the resume itself

**Timeline:** bob approves at T=1h. `rundeck-b`'s worker claims at T=1h0s2. `rundeck-b` crashes at T=1h0s5 (mid-resume, before any deploy step has run).

| # | State after `rundeck-b` crash | Gap |
|---|---|---|
| State | Execution row: `status='running'`, `serverNodeUUID=rundeck-b`. Resume thread on `rundeck-b` dead mid-rehydration. Log file: reopened in append mode but nothing written yet. | **[GAP G35: resume mid-failure]** `rundeck-a` is still dead (walkthrough 4 main case). Now `rundeck-b` is also dead. Are there any other nodes? If the cluster is two-node, there are no other nodes, and when either node comes back up, it will find this execution as `status='running'` `serverNodeUUID=<crashed node>` and clean it up as orphaned. Execution ends as `incomplete`. The approval is lost; operator must re-trigger. Acceptable for v1 but **explicit in spec.** |

### Fourth crash scenario variant: cluster split brain

**Timeline:** Network partitions `rundeck-a` and `rundeck-b`. Both still running, but neither can reach the other. DB is still available (e.g., Postgres is on a separate VPC reachable by both).

| # | State | Gap |
|---|---|---|
| State | Execution `42` is `waiting`. Both nodes' pollers tick. Both read `resume_ready=true`. Both attempt atomic claim. DB serializes them — exactly one wins. | Spec §4 I2 holds — no split brain risk for resume claims. |
| But | If the shared FS is also partitioned (one node can reach it, the other can't), the losing-side claim was fine but the winner cannot read the log file. Writer open fails. | **[GAP G36: shared FS partial failure on resume node]** Spec §10.2 has "log file missing" → execution marked `failed`. But this is the resume-worker-side behavior: it failed to open, so the execution is `status='failed'`? Or should the claim be rolled back and another worker try? Current spec: the worker that claimed and failed to open marks the execution failed and the failure sticks. If the other node could have succeeded (FS is reachable from there), the execution is prematurely lost. Decision: on resume-open failure, RELEASE the claim (set `serverNodeUUID=NULL` again) and let the other node try. Only mark `failed` if all attempts exhausted. Requires a retry count on the execution row. |

### What Walkthrough 4 proves

- The main crash case (`rundeck-a` dies after suspend commits) works cleanly via the shared-DB + unowned model. No data loss, no manual intervention required.
- Crashes before the suspend commit are handled by the pre-existing orphan reaper — no new handling needed, pre-existing behavior unchanged.
- Crashes mid-resume on the second node leave the execution in a recoverable (if lossy — approval must be re-issued) state.

### What Walkthrough 4 found

**4 gaps**, mostly about crash-window corner cases (G34: fsync on suspend close, G35: resume-mid-failure documentation, G36: claim-release-on-resume-open-failure).

---

## Gap summary

Consolidated gap list, prioritized by severity.

### Blocking (must be resolved before Phase 3 begins)

| ID | Title | Walkthrough | Resolution direction |
|---|---|---|---|
| G10 | No notification path for pending approvals | 1 | Add notification event `execution.waiting-approval`; reuse existing Rundeck notification plugin system. Belongs in spec's new §15 "First-class confirm plugin". |
| G12 | Approval API endpoint not defined | 1 | New `ApprovalController` with `POST /api/{v}/execution/{id}/approve`, `POST /api/{v}/execution/{id}/deny`, `GET /api/{v}/execution/{id}/approval/status`. Spec §15. |
| G13 | New ACL action `approve` on `execution` resource | 1 | Add to `ExecutionResourceActionType` / `AuthConstants` / aclpolicy schema. Document in spec §15. |
| G15 | `ResumePayload` polymorphism / subtypes | 1 | Decision: `ResumePayload` is an interface with a `String getType()` discriminator. Known subtypes include `ApprovalPayload`. Third-party suspend-capable plugins define their own subtypes via Jackson registration. Spec §2.1 + §15. |
| G16 | `markResumeReady` semantics when execution no longer waiting | 1 | Method returns `false` if `status != 'waiting'`. Document in spec §8.1. |
| G19 | Resume worker Grails context/security/transaction setup | 1 | Spec §14 open question 4 — MUST be resolved via explicit factoring or documented setup replication. Blocker. |
| G22 | Approval audit trail location | 1 | Decision: (a) immutable log-event type in the execution log stream, +  (b) a new `ExecutionApproval` domain row keyed on exec id with approver identity + timestamp + comment + approved flag. Both. Spec §15. |
| G30 | Plugin configuration drift across suspend/resume | 3 | Decision: freeze plugin configuration at suspend time; stash in `suspend_metadata` JSON column on Execution. Plugin on resume reads from frozen config, not current job definition. Spec §2.3 + §7.3 + §15. |
| G31 | Terminal-write factoring MANDATORY | 3 | Promote spec §12 decision 10 from conditional to required. Factor `finishExecution` / `saveState` terminal-write logic into a `ExecutionTerminalWriteService` or similar, invoked symmetrically by `ExecutionJob` (original path) and `ExecutionResumeService` (resume path). Spec §8.4 rewrite. |

### High priority (should be resolved in spec before Phase 3, but not blocking Wave 0)

| ID | Title | Walkthrough | Resolution direction |
|---|---|---|---|
| G1 | `SuspendRequest.payload` vs. structured suspend metadata | 1 | Separate fields. `SuspendRequest.payload` remains opaque for plugin-internal use. Add `SuspendRequest.metadata` for structured metadata visible to the framework (approver roles, message, timeoutAction). Metadata is stored in its own column (see G28). |
| G2 | Policy validation vs. plugin-level authorization | 1 | Explicit spec text: `SuspensionPolicy` validates workflow-level suspendability only. Approver authorization is a plugin concern, enforced by the `ApprovalController`. Spec §4 + §15. |
| G3 | Return envelope shape from `processOperations` | 1 | Decision: modify `WorkflowSystemState` to carry a `boolean suspended` + `List<SuspendRequest> suspendRequests`. Spec §6.1 adds concrete type. |
| G4 | Which listener events fire / don't fire on suspend | 1 | Spec §6.1 enumerates: `finishWorkflowExecution` does NOT fire. `finishWorkflowItem` / `finishExecuteNodeStep` for the suspended step: **they DO fire with the suspended result**, so listeners get a chance to observe the suspension. Add a `isSuspended()` check on the result they receive. Spec §6.1. |
| G11 | Minimum v1 UI for waiting executions | 1 | Decision: v1 ships a minimal UI affordance — execution detail page shows the suspend `message` (from metadata) and "Approve" / "Deny" buttons when `status='waiting'` and the authed user has `approve` ACL. No custom list view, no badge count. Spec §13 updated, spec §15 adds the minimum UI. |
| G14 | `requiredApproverRoles` read path | 1 + 3 | Resolved by G28 — suspend metadata column. Controller reads from the column, not from checkpoint blob. |
| G17 | `dateStarted` vs. reaper threshold on resume | 1 | Reaper must use `wait_started_at` for suspended-was-recently-resumed rows, not just `dateStarted`. OR reaper uses `dateStarted < before AND status = 'running' AND NOT (id was recently waiting)` — complex. Cleaner: add a `last_resumed_at` column; reaper uses `greatest(dateStarted, coalesce(last_resumed_at, dateStarted)) < before`. Spec §2.3. |
| G28 | Suspend metadata in its own column | 3 | New column `suspend_metadata` CLOB (JSON). Populated at suspend time from `SuspendRequest.metadata` (G1). Cleared at final completion. Referenced by the approval API and notification path. Spec §2.3, §7.1. |
| G29 | Step plugin class not loadable on resume node | 3 | Spec §10.2 gains "step plugin class not loadable" row. Decision: release claim, let another node try. If all nodes exhaust, mark failed. |
| G36 | Claim release on resume-open failure | 4 | Add retry mechanism: on resume failure (log open, context build, plugin load), release claim (`serverNodeUUID=NULL`), increment a `resume_attempt_count` column, let polling pick up again. After N attempts (default 3), mark failed with a clear error. Spec §10.2 + §2.3. |

### Medium priority (clarifications in spec text, not design changes)

| ID | Title | Walkthrough | Resolution direction |
|---|---|---|---|
| G5 | Who calls `writer.suspend()` | 1 | Spec §6.1 step 5 explicitly: inside `ExecutionService.onWorkflowSuspended`, before DB transaction begin. |
| G6 | `onWorkflowSuspended` owns the log close | 1 | Same as G5. Clarify spec §8.2. |
| G7 | Plugin cross-suspend state encoding | 1 | Clarify spec §5.1 contract with explicit example and "state must be in SuspendRequest.payload or framework" rule. |
| G8 | File descriptor invariant | 1 | Spec §4 new invariant "I10: no writer-side file descriptor is held by any process on any node between suspend-close and resume-open." |
| G9 | `saveState` signature receives `RunResult` | 1 | Implementation detail — confirm during Wave 2 read. Not a spec change. |
| G18 | Listener in-memory state across begin/end | 1 | Spec §5.1 contract clause: "execution listener state is NOT preserved across suspend/resume. Listeners that hold cross-call state must tolerate suspension or opt out via the policy." |
| G20 | Step result data Jackson-serializable | 1 | Spec §7.3 contract: "step result `data` field must round-trip through Jackson. Complex types must flatten to primitives before checkpoint." |
| G21 | Fresh plugin instance on resume | 1 | Spec §5.1 contract clause: "plugin instance is not preserved; a fresh instance is constructed on resume." |
| G23 | Clear checkpoint on success | 1 | Spec §4 I4 clause: "on transition to terminal state, all suspend-related columns (checkpoint_data, suspend_metadata, resume_ready, resume_payload) are cleared." |
| G24 | Single-node mode uses same claim machinery | 2 | Spec §9 clarifies "single-node mode uses the same atomic claim as cluster mode." |
| G25 | Latent portability trap single-node → cluster | 2 | Spec §11 test strategy: "every integration test runs in a mode that forces fresh context rehydration even on single-node, simulating cluster behavior." |
| G26 | Shared FS close-to-open consistency required | 3 | Spec §9.4 adds explicit requirement. Operator docs updated. |
| G27 | Job definition visibility on resume node | 3 | No change needed — Grails domain reads are DB-backed. Clarify in spec §9. |
| G32 | Notifications from resuming node | 3 | Spec §9 clarifies "notifications fire from the node that completes the execution, not the node that originated it." |
| G33 | Reaper pre-existing cross-node limitation | 4 | Spec §9 note: "resume/suspend inherits existing Rundeck cluster-mode limitation: running (non-suspended) executions on a crashed node are not taken over by other nodes; they wait for the crashed node to restart." |
| G34 | fsync on suspend close | 4 | Spec §5.2 contract: "`CheckpointableStreamingLogWriter.suspend()` MUST ensure data is durable on disk before returning (fsync or equivalent)." |
| G35 | Resume mid-failure documentation | 4 | Spec §10 adds a row for "resume-side crash; execution remains in an inconsistent claimed-but-not-progressing state; reaped normally by originating node or by other mechanism." |

### Total: 36 gaps found

- **9 blocking** — design changes required before Phase 3 Wave 0.
- **10 high priority** — significant spec additions but compatible with Wave 0 starting on Phase 0 types.
- **17 medium priority** — spec clarifications / contract tightening.

---

## Spec amendments (to be applied next)

The spec at `docs/specs/workflow-suspend-resume.md` needs the following structural changes:

### New §15 — First-class approval step plugin

A new section describing the built-in `ConfirmWorkflowStep`, its configuration fields, its behavior, the `ApprovalPayload` subtype of `ResumePayload`, the `ApprovalController` API endpoints, the new ACL action, the audit trail persistence, the minimum UI affordance, and the notification event. Resolves G10, G11, G12, G13, G15, G22, G28.

### §2.3 additions — new columns

- `suspend_metadata` CLOB nullable — structured metadata visible to the framework (approver roles, message, timeoutAction). Populated at suspend time, cleared at terminal transition.
- `last_resumed_at` TIMESTAMP nullable — tracks the most recent resume, used by the reaper to avoid false-orphan-detection of recently-resumed rows.
- `resume_attempt_count` INTEGER default 0 — increments on each failed resume attempt; after N failures, execution marked failed.

### §2.1 refinement — `ResumePayload` interface

`ResumePayload` becomes an interface with a `String getType()` discriminator. `ApprovalPayload` is a known implementation. Third-party suspend-capable plugins can define their own subtypes via Jackson polymorphism registration.

### §4 invariant additions

- **I10:** no writer-side file descriptor is held by any process on any node between suspend-close and resume-open.
- **I11:** at terminal transition, all suspend-related columns are cleared (`checkpoint_data`, `suspend_metadata`, `resume_ready`, `resume_payload`, `wait_started_at`, `wait_timeout_at`, `last_resumed_at`, `resume_attempt_count`).

### §5.1 contract clauses added

- Plugin instance is NOT preserved across suspend.
- Execution listener state is NOT preserved across suspend.
- Plugin-held state must be encoded in `SuspendRequest.payload` or framework; local variables and instance fields are lost.

### §5.2 contract clauses added

- `suspend()` MUST fsync data to disk before returning.

### §6.1 clarifications

- Return envelope from `processOperations` is a modified `WorkflowSystemState` with `boolean suspended` + `List<SuspendRequest>`.
- `finishWorkflowExecution` listener callback does NOT fire on suspend.
- Step-level listener callbacks for the suspended step DO fire, with the suspended result; listeners must check `result.isSuspended()`.
- `writer.suspend()` is called from `ExecutionService.onWorkflowSuspended` before the DB transaction begins.

### §8.4 rewrite — terminal-write factoring mandatory

Spec §12 decision 10 is promoted from conditional to mandatory. A new `ExecutionTerminalWriteService` (or equivalent factoring) owns `dateCompleted`, status transition, completion notifications, and log footer write. Invoked by both `ExecutionJob` (original path) and `ExecutionResumeService.resumeExecution` → terminal path.

### §10 new error rows

- Step plugin class not loadable on resume node → release claim + retry.
- Resume thread crash before first step re-invocation → claim released via reaper path, `resume_attempt_count` incremented by next worker.
- Log writer open fails on resume node (NFS partial, permission, missing file) → release claim + retry (up to `resume_attempt_count` max).

### §11 test strategy additions

- Cluster-simulating mode for integration tests: every test forces fresh context rehydration, simulating cluster behavior even on single-node CI.
- Crash-window integration tests: fault injection at I5 steps 1–5 to verify each window's recovery behavior.
- Concurrent claim race test: N parallel workers attempt to claim the same row; exactly one succeeds.

### §12 decision changes

- Decision 10: "factor out if needed" → "factor out, required." Promoted to mandatory.
- New decision 14: plugin configuration at suspend time is frozen; resume reads from suspend metadata, not current job definition.
- New decision 15: resume-open failure releases the claim and retries up to N times (default 3) before marking failed.
- New decision 16: `ResumePayload` is a polymorphic interface with `getType()` discriminator.

### §13 not-in-scope updates

- Multi-approver quorum stays out of scope.
- Notification integration changes from "out of scope" to "minimal: emits a single event type; existing notification plugins consume." Notification plugin *authoring* remains out of scope.
- UI polish beyond minimum approve/deny affordance stays out of scope.

### §14 open questions resolved

- Q1 (`StepExecutionContext` default methods vs. sub-interface): resolved → default methods.
- Q4 (resume thread Grails setup): resolved → factoring mandatory (G19 → §8.4 rewrite).
- Q5 (UI/API/metric on rapid status oscillation): requires integration test (added to §11).
- Q8 (abort-during-suspend race): requires concurrency test (added to §11).

Remaining open: Q2 (`WFSharedContext` serializability), Q3 (`UserAndRolesAuthContext` serializability), Q6 (log file size bound), Q7 (checkpoint blob size bound). These require reading code during Wave 0.

---

**⏸ PAUSE. Present walkthroughs + gap summary to user. Apply spec amendments next if approved. Do not begin Phase 3 implementation until spec is amended and reviewed.**
