# cycle/workflow-suspend-resume

**Status:** Phase 2 spec + walkthroughs complete, revision 2 reconciled (36 gaps resolved). Ready for Phase 2 user review. No Phase 3 implementation started.

**Goal:** Add an async suspend/resume primitive to Rundeck's workflow engine so long-waiting steps (approval gates, human-in-the-loop, external-event wait) release their JVM thread while waiting, and a two-node Rundeck cluster sharing a database + filesystem can resume the waiting execution on either node. Ship two first-class consumers: a built-in `ConfirmWorkflowStep` plugin, and an operator-initiated pause/resume feature that works on any running multi-step execution.

**Artifact set:**

| Artifact | Path | Phase |
|---|---|---|
| Audit | `docs/audits/workflow-suspend-resume.md` | 1 (complete) |
| Walkthroughs | `docs/walkthroughs/workflow-suspend-resume.md` | 1/2 (complete, 36 gaps found) |
| Spec (primitive) | `docs/specs/workflow-suspend-resume.md` | 2 (complete, revision 2) |
| Spec (consumer: confirm plugin) | `docs/specs/confirm-workflow-step.md` | 2 (complete, revision 1) |
| Spec (consumer: operator pause) | `docs/specs/operator-pause.md` | 2 (complete, revision 1) |
| Test plan | `docs/specs/test-plan-workflow-suspend-resume.md` | 2 (complete, revision 1) |
| Cycle manifest | `docs/cycles/workflow-suspend-resume.md` (this file) | 2 (reconciled) |

**Reference consumer:** `com.dtolabs.rundeck.plugin.confirm.ConfirmWorkflowStep`, a new built-in plugin shipping in this cycle. See `confirm-workflow-step.md` for its own spec. The third-party `rx-rundeck-plugin`'s `RxConfirmWorkflowStep` is the motivating use case but its port is a downstream cycle, not part of this one.

---

## Motivation

Rundeck's workflow engine runs each execution on a dedicated thread (`WorkflowExecutionServiceThread`) with synchronous step execution. A plugin that polls for an external event pins one OS thread per pending wait — plus thread-locals, DB connection context, and any buffered log state — for the entire duration. There is no framework-level mitigation because the engine has no suspend primitive.

The motivating real-world case is approval workflows: a deploy is gated on a human confirmation. Today that forces one thread per pending approval, blocking for hours. Scale that to dozens of concurrent approvals and the JVM-thread budget runs out long before anything interesting happens.

## Non-goals

- Parallel workflow strategy suspension (forbidden at `SuspensionPolicy`).
- Sub-workflow suspension cascade (forbidden at `SuspensionPolicy`).
- Plugin `ContextComponent`s holding live service references across suspend (forbidden unless they implement `CheckpointableContextComponent`).
- Sub-second resume latency (DB-polling worker at 1–2s is acceptable; human latency dominates approval flows).
- UI polish for waiting executions beyond the confirm plugin's + operator-pause's minimum affordances.
- Third-party streaming log writer plugins without `CheckpointableStreamingLogWriter` (rejected at suspend time).
- Multi-confirmer quorum, custom decision sets, free-text prompts (all deferred to future cycles).
- Mid-step operator pause (only at step boundaries).
- Sub-workflow operator pause (top-level only).
- Scheduled pause ("pause at 14:00"), auto-pause on conditions, bulk pause. Only immediate per-execution pause.
- Permanent pause/resume audit table. Operational actions are observable via existing status history + notification events.
- Dashboard / inbox / header notification center for waiting executions. Deferred to follow-up cycle.
- Dynamic `PreNextStepHook` registration (only built-in hooks registered at engine construction time).
- `rd pause` / `rd confirm` CLI subcommands (follow-up cycles; API supports them today).
- Porting `rx-rundeck-plugin` to the new primitive (downstream cycle).

---

## Design summary

Full design in `docs/specs/workflow-suspend-resume.md` and `docs/specs/confirm-workflow-step.md`. Load-bearing decisions:

- **Unowned-on-suspend ownership.** `serverNodeUUID = NULL` while waiting. DB atomic UPDATE claim mirroring `ScheduledExecutionService.claimScheduledJobs`. No liveness detection required.
- **DB-polling resume worker per node.** Quartz is RAM-only (`QuartzConfig.groovy:21 jdbcStore=false`) so cross-node dispatch cannot use Quartz. Spring-scheduled bean, 2s tick.
- **Mandatory terminal-write factoring.** New `ExecutionTerminalWriteService` owns `dateCompleted` setting, completion notifications, log footer write, and column clearing. Invoked symmetrically by `ExecutionJob` (original start) and `ExecutionResumeService` (resume). Not optional — walkthrough 3 proved the resume worker on a second cluster node is outside Quartz and cannot reach Quartz-coupled terminal-write logic.
- **Configuration freeze at suspend time.** Plugin config resolved at suspend, stored in `Execution.suspend_metadata`, read from frozen metadata on resume. Mid-flight job definition edits do not retroactively affect resumed behavior.
- **Polymorphic `ResumePayload`.** Interface with `getType()` discriminator; `ConfirmationPayload` is the built-in subtype; third-party plugins register subtypes via Jackson.
- **Single log writer SPI change.** `FSStreamingLogWriter` gains `suspend()` (flush + fsync + close, no footer) and the factory gains `openForResume()` (append mode, no header). Reader path unchanged — it already tolerates footerless files.
- **First-class confirm plugin.** New built-in `ConfirmWorkflowStep` + `ApiConfirmController` + `confirm` ACL action + `ExecutionConfirmation` audit domain + `execution.waiting-confirmation` notification event + minimum UI affordance. Ships in the same cycle as the primitive.
- **First-class operator-pause feature.** New `pause_requested` column + engine boundary hook + `ApiOperatorPauseController` (pause/resume/status) + `pause` ACL action + `OperatorResumePayload` ResumePayload subtype + `execution.waiting-operator-pause` notification event + minimum UI affordance (pause/resume buttons on execution detail page). Works on any multi-step execution regardless of whether its workflow contains confirm steps. Pause takes effect at the next step boundary, not mid-step. Single-step workflows are rejected at the API with `no-remaining-boundary`.

---

## Wave sequence

Seven waves. Strict ordering: each wave reaches a plateau where the system is correct, tested, and internally consistent. Stopping between waves is partial work.

### Wave 0 — Types and contracts

Foundational types. Nothing is called from production code yet; Wave 1 consumes them.

| Action | File | Intent |
|---|---|---|
| New | `core/.../execution/workflow/suspend/SuspendRequest.java` | Immutable serializable value: `token`, `payload` (plugin-internal opaque), `metadata` (framework-visible structured), `timeoutMs`, `reason`, `waitingFor`. `java.io.Serializable` + Jackson-compatible. |
| New | `core/.../execution/workflow/suspend/ResumePayload.java` | **Polymorphic interface** with `default String getType()`. Registered via Jackson `@JsonTypeInfo` for subtype dispatch. |
| New | `core/.../execution/workflow/suspend/SuspendedStepResult.java` | Implements `StepExecutionResult`. Carries `SuspendRequest`. `isSuspended()=true`, `isSuccess()=false`. |
| New | `core/.../execution/workflow/suspend/CheckpointableContextComponent.java` | Marker interface with `checkpointState()` + `restoreState(state, framework)`. |
| New | `core/.../execution/workflow/suspend/CheckpointableStreamingLogWriter.java` | Sub-interface of `StreamingLogWriter` with `suspend()` method (must flush + fsync + close without footer). |
| New | `core/.../execution/workflow/suspend/CheckpointableStreamingLogWriterFactory.java` | Factory contract with `openForResume(Execution)`. |
| New | `core/.../execution/workflow/suspend/SuspensionPolicy.java` | Static `validateSuspendable(context, strategy, writer)` throws `SuspensionNotAllowedException` for parallel / sub-workflow / non-checkpointable components / non-checkpointable writer. Not called in Wave 0. |
| New | `core/.../execution/workflow/suspend/SuspensionNotAllowedException.java` | Checked exception. Clear message. |
| New | `core/.../execution/workflow/suspend/SuspensionFailedException.java` | Distinct from NotAllowed: suspension was permitted but failed (serialization, log flush, DB commit). |
| New | `core/.../execution/workflow/suspend/ExecutionCheckpoint.java` | Logical representation of the `checkpoint_data` JSON blob. Holds `version`, `suspendedStepIndex`, `suspendRequest`, `contextData`, `components`, `completedStepResults`. |
| Edit | `core/.../execution/workflow/steps/StepExecutionResult.java` | Add `default boolean isSuspended()` and `default SuspendRequest getSuspendRequest()`. Non-breaking. |
| Edit | `core/.../execution/ExecutionContext.java` or `StepExecutionContext.java` | Add three default methods: `getResumePayload()`, `getSuspendMetadata()`, `suspend(SuspendRequest)`. Default suspend throws `UnsupportedOperationException`. |
| Edit | `core/.../execution/workflow/WorkflowExecutionResult.java` | Add `default boolean isSuspended()` and `default List<SuspendRequest> getSuspendRequests()`. Non-breaking. |

**Verification during Wave 0** (spec §14 open questions):
- Read `WFSharedContextImpl` end-to-end. Confirm `WFSharedContext` is fully Jackson-serializable. If not, this wave surfaces the blocker before any engine code is touched.
- Read `UserAndRolesAuthContext` implementations. Confirm serializability and design the rehydration path.
- Prototype Jackson polymorphic subtype registration across plugin module boundaries (spec §14 Q5). Verify `ConfirmationPayload` in a separate module can be resolved by core's Jackson deserializer.

**Exit criteria:**
- All new types compile.
- Existing `StepExecutionResult` / `StepExecutionContext` / `WorkflowExecutionResult` implementations compile unchanged (default methods inherited).
- Unit tests pass: `SuspendRequest` + `ResumePayload` + `ConfirmationPayload` Jackson round-trip with polymorphic discriminator; `SuspensionPolicy` rejection cases; `SuspendedStepResult.isSuspended()==true`.
- Verification tasks (WFSharedContext, auth context, subtype registration) completed and documented; any blocker escalates before Wave 1 begins.

### Wave 1 — Engine loop change

The load-bearing change. Teach `WorkflowEngineOperationsProcessor` to exit cleanly on a suspended step, and `EngineWorkflowExecutor` to aggregate a suspended workflow result distinct from success/failure.

| Action | File | Intent |
|---|---|---|
| Edit | `core/.../execution/workflow/engine/WorkflowSystemState` | Add `boolean suspended` and `List<SuspendRequest> suspendRequests` fields. (Exact class TBD on read — may need a new envelope wrapper if the existing state is immutable.) |
| Edit | `core/.../execution/workflow/engine/WorkflowEngineOperationsProcessor.java` | In `processStep()` / `continueProcessing()`: on observing a suspended completion in the state change queue, set `suspendRequested` flag, drain already-completed operations, exit loop at next iteration. Return accumulated partial results with `suspended=true` on the envelope. |
| Edit | `core/.../execution/workflow/engine/WorkflowEngine.java` | `processOperations()` returns a result set that may carry "suspended." |
| Edit | `core/.../execution/workflow/EngineWorkflowExecutor.java` | (1) Before `processOperations`, call `SuspensionPolicy.validateSuspendable(context, strategy, writer)`. (2) In result aggregation loop (~`:272-293`), check `isSuspended()` BEFORE `isSuccess()`. (3) Build `WorkflowExecutionResult` with `isSuspended=true`, `workflowSuccess=false`, NOT a failure. (4) Skip `stepFailures` accumulation for suspended steps. |
| Edit | `core/.../execution/workflow/BaseWorkflowExecutor.java` | Teach result handling to treat suspended as a third state: **suppress `finishWorkflowExecution` listener callback** on suspended result, but ALLOW step-level `finishWorkflowItem` / `finishExecuteNodeStep` callbacks to fire with `isSuspended()==true`. |
| Edit | `core/.../execution/workflow/WorkflowExecutionResultImpl.java` (or equivalent) | Override new default methods with stored fields; builder gains `.suspended(List<SuspendRequest>)`. |

**Exit criteria:**
- Unit test: mock step-operation returns `SuspendedStepResult` → processor exits → `WorkflowEngine.processOperations` returns with suspend flagged → `EngineWorkflowExecutor` produces `WorkflowExecutionResult` with `isSuspended=true`, correct step index.
- Unit test: 5-step sequential workflow, step 3 suspends → steps 1–2 in completed results, step 3 as suspended, steps 4–5 absent, workflow result suspended (not failed).
- Unit test: `SuspensionPolicy` rejects parallel strategy, sub-workflow, non-checkpointable component, non-checkpointable writer.
- Unit test: `finishWorkflowExecution` does NOT fire on suspended result; `finishWorkflowItem` DOES fire with `result.isSuspended()==true` (verified via mock listener).
- No existing engine tests regress.

### Wave 2 — Execution lifecycle + schema

Teach Grails + Quartz that `waiting` is non-terminal and to leave `dateCompleted` null.

| Action | File | Intent |
|---|---|---|
| New | `rundeckapp/grails-app/migrations/changelog-<date>-suspend-resume.groovy` | Liquibase: add 9 columns to `rundeck_execution` — `checkpoint_data CLOB`, `suspend_metadata CLOB`, `wait_started_at TIMESTAMP`, `wait_timeout_at TIMESTAMP`, `last_resumed_at TIMESTAMP`, `resume_ready BOOLEAN DEFAULT FALSE`, `resume_payload CLOB`, `resume_attempt_count INTEGER DEFAULT 0`, `pause_requested BOOLEAN DEFAULT FALSE`. Registered in master changelog. |
| New | `rundeckapp/grails-app/migrations/changelog-<date>-execution-waiting-indexes.groovy` | Liquibase: two indexes — `(status, resume_ready, server_node_uuid)` for claim query and `(status, wait_timeout_at)` for timeout sweep. |
| Edit | `rundeckapp/grails-app/domain/rundeck/Execution.groovy` | Add 8 new fields; nullable constraints; CLOB mapping; add `ne('status', ExecutionService.EXECUTION_WAITING)` to `runningExecutionsCriteria` (line ~169–179); update `getExecutionState()` to recognize `waiting`. |
| Edit | `core/.../execution/ExecutionState.java` (or groovy) | Add `waiting` enum value. |
| Edit | `rundeckapp/grails-app/services/rundeck/services/ExecutionService.groovy` | Add `EXECUTION_WAITING = "waiting"` constant (~`:1499-1508`). New methods: `onWorkflowSuspended(execmap, result)` owning I5 steps 1–7 (log flush + writer suspend + DB transaction + metadata write + notification event fire); `findWaitingExecutions(project)`. Modify `abortExecutionDirect` to detect `status='waiting'` and bypass Quartz lookup (I9). |
| Edit | `core/.../execution/workflow/WorkflowExecutionServiceThread.java` | Expose `isSuspended()` + `getResult()` accessor if not already present. |
| Edit | `rundeckapp/grails-app/jobs/rundeck/quartzjobs/ExecutionJob.groovy` | In `executeCommand` after `thread.join()` returns (~`:517`), check `thread.isSuspended()`. If true: call `executionService.onWorkflowSuspended(execmap, thread.result)` and return a new `RunResult(suspended=true)`. In `saveState()` (~`:599-705`): early-return when suspended. For normal path: defer terminal-write to Wave 4 factoring (temporary inline logic with TODO). |

**Wave 2 → Wave 3 dependency:** Wave 2 needs `FSStreamingLogWriter.suspend()` to exist (as a no-op or temporary implementation) for `onWorkflowSuspended` to call. Options: ship Wave 2 with a thin `suspend() { flush(); close(); }` in FSStreamingLogWriter (no fsync, no resume mode yet), then Wave 3 hardens it. OR ship Wave 3 before Wave 2. Decision: **Wave 2 ships with a minimal implementation; Wave 3 makes it correct.** Integration test assertions that depend on resume-after-suspend defer to Wave 4.

**Exit criteria:**
- Migration runs cleanly forward and backward.
- Unit test: `Execution.runningExecutionsCriteria` excludes `waiting`.
- Unit test: `ExecutionService.onWorkflowSuspended` leaves `dateCompleted=null`, clears `serverNodeUUID`, sets `wait_started_at` + `wait_timeout_at`, writes checkpoint + metadata.
- Unit test: `ExecutionJob.saveState` with suspended `RunResult` skips `dateCompleted` and completion notifications (mock notification service zero interactions).
- Unit test: `abortExecutionDirect` on waiting row bypasses Quartz lookup.
- Integration test: mock workflow with a step returning `SuspendedStepResult`, driven end-to-end → execution row ends `status='waiting'`, `dateCompleted=null`, `serverNodeUUID=null`, `checkpoint_data` + `suspend_metadata` populated, `wait_started_at` set, log file NOT footer-closed, zero completion events fired.
- Reaper tick does not touch waiting rows.

### Wave 3 — Log writer SPI hardening

| Action | File | Intent |
|---|---|---|
| Edit | `rundeckapp/src/main/groovy/com/dtolabs/rundeck/app/internal/logging/FSStreamingLogWriter.groovy` | Implements `CheckpointableStreamingLogWriter`. `suspend()` method: `flush() → getFD().sync() → close()`, explicitly does NOT call `formatter.outputFinish()`. Constructor gains a `resumeMode` flag that suppresses `formatter.outputBegin()` at `:63`. |
| Edit | `rundeckapp/grails-app/services/rundeck/services/LogFileStorageService.groovy` | Implements `CheckpointableStreamingLogWriterFactory`. New method `getLogFileWriterForResume(Execution)`: constructs `new FileOutputStream(file, true)` (append mode), instantiates `FSStreamingLogWriter` with `resumeMode=true`. |
| Edit | `core/.../execution/workflow/suspend/SuspensionPolicy.java` | Add writer capability check: inspects configured streaming writer chain; rejects if any entry is not `CheckpointableStreamingLogWriter`. Rejection message names the offending plugin. |

**Exit criteria:**
- Unit test: `FSStreamingLogWriter.suspend()` calls `flush`, `getFD().sync`, `close`, does NOT call `outputFinish` (verified via mock/spy).
- Unit test: resume-mode writer skips `outputBegin()`.
- Integration test: open writer → write events A → suspend → `openForResume` → write events B → close normally → file has exactly one `^text/x-rundeck-log-v2.0^` header, events A then events B in order, exactly one `^END^` footer, parseable by `FSStreamingLogReader` without corruption.
- Unit test: `SuspensionPolicy` rejects non-`CheckpointableStreamingLogWriter`.
- Contract-test harness for third-party implementers (abstract test class).

### Wave 4 — Resume worker + terminal write factoring

| Action | File | Intent |
|---|---|---|
| New | `rundeckapp/grails-app/services/rundeck/services/ExecutionTerminalWriteService.groovy` | `writeTerminal(execution, result)` owns: `dateCompleted` set, final status transition, completion notifications, log writer close (footer written via normal `close()`), clearing all suspend columns per I11. Single authoritative terminal-write path. |
| New | `rundeckapp/grails-app/services/rundeck/services/ExecutionResumeService.groovy` | Spring-scheduled bean. `@Scheduled scheduledResumePoll()`: queries eligible rows, attempts atomic claim (mirrors `claimScheduledJobs` pattern), invokes `resumeExecution` on claim. `@Scheduled scheduledTimeoutSweep()`: finds expired `wait_timeout_at` rows, writes synthetic timeout payload. `markResumeReady(execId, payload)`: API entry for consumer controllers; returns false if row no longer waiting. `resumeExecution(exec)`: rehydrate context, open log writer in resume mode, spawn `WorkflowExecutionServiceThread`, enter `EngineWorkflowExecutor.executeWorkflowResume`. Release-claim-and-retry on rehydration failure; `resume_attempt_count` bookkeeping; exhaustion → `ExecutionTerminalWriteService.writeTerminal(failed)`. |
| Edit | `core/.../execution/workflow/EngineWorkflowExecutor.java` | Add `executeWorkflowResume(context, item, checkpoint, resumePayload)` method. Primes `MutableStateObj` from checkpoint's completed-step-results; sets `context.resumePayload` + `context.suspendMetadata`; re-invokes suspended step; continues processor loop. ALSO: add the between-steps hook point per spec §6.3. After each step result is observed in the aggregation loop (and BEFORE the next step begins), invoke registered `PreNextStepHook` implementations; hook may return `SynthesizeSuspension(SuspendRequest)` causing the engine to treat the just-completed step's result as a synthesized suspension. |
| New | `core/.../execution/workflow/suspend/PreNextStepHook.java` | Interface for registered engine hooks: `HookResult evaluate(StepExecutionContext context, int justCompletedStepIndex, int totalStepCount)` returning `ProceedToNextStep` or `SynthesizeSuspension(SuspendRequest)`. Hooks MUST be cheap, idempotent, and side-effect-free (except for the suspension itself, which is performed by the engine). |
| New | `core/.../execution/workflow/engine/HookRegistry.java` (or similar) | Spring/Grails-discovered registration for hooks at engine construction time. No dynamic registration. |
| Edit | `rundeckapp/grails-app/services/rundeck/services/ExecutionService.groovy` | Implement `buildExecutionContextFromCheckpoint(exec, checkpoint)`: rebuild `ExecutionContextImpl` from checkpoint data + local framework / listeners / logging manager / services. |
| Edit | `rundeckapp/grails-app/jobs/rundeck/quartzjobs/ExecutionJob.groovy` | `saveState()` normal (non-suspended) path: delegate to `executionTerminalWriteService.writeTerminal(...)`. Remove inline terminal logic. |
| Edit | `rundeckapp/grails-app/services/rundeck/services/ExecutionService.groovy` (again) | `abortExecutionDirect` for `waiting` rows: reopen writer via `openForResume`, invoke `ExecutionTerminalWriteService.writeTerminal` with aborted outcome. |

**Configuration:**
- `rundeck.execution.resume.pollIntervalMs` (default `2000`)
- `rundeck.execution.resume.timeoutSweepIntervalMs` (default `30000`)
- `rundeck.execution.resume.maxAttempts` (default `3`)

**Exit criteria:**
- Unit test: `ExecutionTerminalWriteService.writeTerminal` clears all suspend-related columns per I11, sets `dateCompleted`, fires notifications, closes log writer (footer written).
- Unit test: `ExecutionResumeService.markResumeReady` returns false if `status != 'waiting'`.
- Unit test: claim race — two workers simultaneously, exactly one UPDATE returns 1 row, the other returns 0.
- Unit test: resume failure (fault-injected rehydration error) releases claim and increments `resume_attempt_count`.
- Unit test: `resume_attempt_count >= maxAttempts` exhaustion marks execution `failed` via `ExecutionTerminalWriteService`.
- Unit test: `scheduledTimeoutSweep` finds `wait_timeout_at <= now()` rows and writes synthetic timeout payloads.
- Integration test (single-node): mock suspendable step → suspend → resume worker claims → completes → verify `ExecutionTerminalWriteService` wrote terminal state, log file has footer.
- Integration test (simulated two-node): suspend on "node A", resume on "node B" (via test-only context-forcing), log file has correct structure, terminal write happens on node B.
- Integration test: simulated node failure mid-resume (kill thread between claim and first step invocation) → claim released by reaper → next worker tick retries successfully.
- Integration test: fault injection at each I5 step (before flush, between flush and suspend, between suspend and DB commit, after commit, during saveState) → verify recovery behavior at each window.
- Cluster-simulating mode test harness: every integration test forces fresh context rehydration on resume to simulate cluster behavior on single-node CI.

### Wave 5 — First-class confirm plugin

Ships the built-in `ConfirmWorkflowStep` + everything in `docs/specs/confirm-workflow-step.md`.

| Action | File / area | Intent |
|---|---|---|
| New | `plugins/confirm-plugin/` (new gradle module) or inlined in existing core plugins | Gradle module for the confirm plugin. Structure TBD on read of existing plugin modules. |
| New | `plugins/confirm-plugin/src/main/java/.../ConfirmWorkflowStep.java` | `WorkflowStep` plugin class with config properties (message, timeout, timeoutAction, requiredConfirmerRoles, decisionSet, minConfirmations). Implements suspend + resume branches per `confirm-workflow-step.md §5`. |
| New | `plugins/confirm-plugin/src/main/java/.../ConfirmationPayload.java` | `ResumePayload` subtype with `type="confirmation"` discriminator + Jackson registration. |
| New | `plugins/confirm-plugin/src/main/resources/META-INF/services/...` | ServiceLoader wiring for Jackson subtype registration (path TBD from Wave 0 prototype). |
| New | `rundeckapp/grails-app/migrations/changelog-<date>-execution-confirmation.groovy` | Liquibase: create `rundeck_execution_confirmation` table per `confirm-workflow-step.md §7`. Index on `execution_id`. |
| New | `rundeckapp/grails-app/domain/rundeck/ExecutionConfirmation.groovy` | Immutable audit domain. |
| New | `rundeckapp/grails-app/controllers/rundeck/controllers/ApiConfirmController.groovy` | `POST /api/{v}/execution/{id}/confirm`, `GET /confirm/status`, `GET /confirmations`. Full authorization + validation flow per `confirm-workflow-step.md §8`. |
| Edit | `core/.../authorization/AuthConstants.java` | Add `ACTION_CONFIRM = "confirm"`. |
| Edit | Rundeck authorization policy engine | Recognize `confirm` action on `execution` resource in aclpolicy.yaml parsing + enforcement. |
| Edit | aclpolicy.yaml schema documentation | Add `confirm` to execution action enumeration. |
| Edit | Default aclpolicy.yaml in Rundeck distribution | Add `confirm` to `admin` role's allowed actions. Do NOT add to lower roles. |
| Edit | `rundeckapp/grails-app/services/rundeck/services/ExecutionService.groovy` | In `onWorkflowSuspended`: after DB commit, if `suspend_metadata.type == "confirmation"`, fire `execution.waiting-confirmation` notification event via existing notification service. |
| Edit | `rundeckapp/grails-app/services/rundeck/services/NotificationService.groovy` (or equivalent) | Register the new event type in the event type enumeration so consumer plugins (email, Slack, webhook) can subscribe via job notification config. |
| New | Minimum UI affordance | Execution detail page (`rundeckapp/grails-app/views/execution/follow.gsp` or equivalent): when `status='waiting'` AND `suspend_metadata.type == "confirmation"`, show resolved `message`, one button per `decisionSet` entry, comment textarea, buttons enabled/disabled via `GET /confirm/status` → `callerCanConfirm`. |

**Exit criteria:**
- Unit tests: `ConfirmWorkflowStep` first-invocation constructs correct `SuspendRequest` with metadata; resume-invocation branches on every decision/timeout case; config freeze resolves interpolation at suspend time; `ConfirmationPayload` Jackson round-trip with discriminator.
- Grails tests: `ExecutionConfirmation` domain immutability; `ApiConfirmController.confirm` happy path + 409 + 403 + 400; ACL `confirm` action enforced; `waiting-confirmation` event fires on confirm suspensions.
- Integration test: full single-node approve flow (alice runs `prod-deploy-v4`, build succeeds, confirm suspends, bob POSTs `/confirm approve`, deploy runs, succeeds). Assert: one `ExecutionConfirmation` row, execution `status=succeeded`.
- Integration test: full single-node deny flow → deploy never runs, execution `status=failed`.
- Integration test: timeout-deny, timeout-approve, timeout-fail flows.
- Integration test: two-node cluster — POST lands on node B while execution suspended on node A, resume claimed by either node, confirmation row accurately recorded.
- Integration test: config freeze — edit `message` after suspend, confirm uses pre-edit message.
- Integration test: carol (no `confirm` ACL) POSTs → 403 Forbidden, execution remains `waiting`.
- Integration test: concurrent POSTs approve + deny race — exactly one wins (201), other gets 409, winner's decision matches final outcome.

### Wave 6 — First-class operator-pause feature

Ships the operator-initiated pause/resume feature per `docs/specs/operator-pause.md`. Depends on Wave 4's engine boundary hook being in place.

| Action | File / area | Intent |
|---|---|---|
| New | `core/.../execution/workflow/suspend/operatorpause/OperatorResumePayload.java` | `ResumePayload` subtype with `type="operator-resume"` discriminator. Carries `resumedBy`, `resumedAt`, `comment`. Jackson registration via the mechanism established in Wave 0. |
| New | `core/.../execution/workflow/suspend/operatorpause/OperatorPauseHook.java` | `PreNextStepHook` implementation: reads `Execution.pause_requested`; if true AND workflow has remaining steps, returns `SynthesizeSuspension(SuspendRequest)` with metadata `{type: "operator-pause", requestedBy, requestedAt, reason}`. Side-effect-free (the engine performs the DB clear as part of the synthesis transaction). |
| New | `rundeckapp/grails-app/controllers/rundeck/controllers/ApiOperatorPauseController.groovy` | `POST /api/{v}/execution/{id}/pause`, `POST /resume`, `GET /pause/status`. Full authorization + validation flow per `operator-pause.md §6`. The `no-remaining-boundary` 409 check reads the job definition's step count + current execution index. |
| Edit | `core/.../authorization/AuthConstants.java` | Add `ACTION_PAUSE = "pause"`. |
| Edit | Authorization policy engine | Recognize `pause` on `execution` resource. |
| Edit | aclpolicy.yaml schema documentation | Add `pause` to execution action enumeration. |
| Edit | Default aclpolicy.yaml | Add `pause` to `admin` role allowed actions. |
| Edit | `rundeckapp/grails-app/services/rundeck/services/ExecutionService.groovy` | In `onWorkflowSuspended`: after DB commit, if `suspend_metadata.type == "operator-pause"`, fire `execution.waiting-operator-pause` notification event. |
| Edit | Execution detail page (GSP or equivalent) | Add "Pause" button next to "Abort" when `status='running'`. Add "Resume" button when `status='waiting'` AND `suspend_metadata.type='operator-pause'`. Enable/disable based on `pause` ACL. |
| Edit | Execution list page | Add `waiting` to the status filter dropdown. Minimal change — no custom list view. |

**Exit criteria:**
- Unit tests: `OperatorResumePayload` Jackson round-trip with discriminator; `OperatorPauseHook.evaluate` returns `SynthesizeSuspension` when flag set, `ProceedToNextStep` otherwise; no synthesis on final step boundary.
- Grails tests: `ApiOperatorPauseController.pause` happy + 409 (not running, sub-workflow, no-remaining-boundary) + 403; `resume` happy + 409 + 403; `GET /pause/status` correct in both running-with-pending-pause and waiting-after-pause states; ACL `pause` action enforced; `waiting-operator-pause` event fires on synthesis; `pause_requested` flag cleared on synthesis AND on terminal per I11.
- Integration tests (per `operator-pause.md §12`):
  - Full multi-step pause/resume flow.
  - Pause + confirmation sequential scenario from `operator-pause.md §10`.
  - Single-step workflow → 409.
  - Sub-workflow pause → 409.
  - Pause during final step → 409.
  - Pause request on waiting execution → 409.
  - Resume on confirmation-type row → 409.
  - Cluster-simulating: pause request on "node B" while execution on "node A"; engine hook on A observes, synthesizes, A persists, resume from either node.
  - Abort of an operator-paused execution transitions directly to aborted.
- Execution list page renders `waiting` status in the filter dropdown (manual UI verification).

### Wave 7 — Reference-consumer dogfooding (optional)

**Scope:** proof-of-concept port of `RxConfirmWorkflowStep` in the `rx-rundeck-plugin` repo to use `context.suspend(...)` instead of the polling loop. Lives in a separate repo; this cycle does not ship the port itself but MAY include a proof-of-concept branch/PR to validate that the primitive works for a real third-party consumer outside `core/`.

**Exit criteria** (aspirational, not blocking cycle completion):
- `rx-rundeck-plugin` branch compiles against the published Rundeck SDK after this cycle ships.
- A manual end-to-end test: the ported `RxConfirmWorkflowStep` suspends cleanly and resumes on approval.
- Feedback captured in a follow-up cycle document.

This wave is **not required** for the cycle to be considered complete. It is a validation vehicle.

---

## Exit criteria (cycle-wide)

A Rundeck OSS build with all waves merged must satisfy:

1. A workflow containing a built-in `confirm` step compiles and executes.
2. alice (with `run` ACL) can start the workflow from the UI or API.
3. The confirm step suspends cleanly; the execution thread is released; the log file is footerless; `Execution.status = 'waiting'`; `serverNodeUUID = NULL`.
4. An `execution.waiting-confirmation` notification event fires once, consumable by existing notification plugins.
5. bob (with `confirm` ACL and any required role) can approve or deny via `POST /api/{v}/execution/{id}/confirm` on any cluster node.
6. On approval, the execution resumes via a DB-polling worker claim, possibly on a different cluster node.
7. On approval, the execution proceeds through remaining steps and terminates normally; `dateCompleted` set once; completion notifications fire once; log file closed with one footer.
8. An `ExecutionConfirmation` row records the confirmation event immutably.
9. On node crash while waiting, another node's resume worker can claim and complete the execution.
10. On timeout (no approval within `wait_timeout_at`), the timeout sweep delivers a synthetic payload; the step fails per its `timeoutAction`.
11. Abort on a waiting execution transitions directly to aborted; log file closed with footer; notifications fire.
12. `SuspensionPolicy` rejects parallel strategy, sub-workflow, non-checkpointable components, non-checkpointable writers — all with clear error messages.
13. alice can call `POST /pause` on a running multi-step execution; the execution transitions to `waiting` with `suspend_metadata.type='operator-pause'` at the next step boundary; alice can later call `POST /resume` and the execution continues to completion.
14. `POST /pause` on a single-step workflow returns 409 with reason `no-remaining-boundary`; the execution proceeds normally.
15. A workflow containing both a confirm step and an operator-pause request in sequence resolves correctly: pause acts first (between earlier steps), then confirm suspends (when its step is reached), both resolved in order.
16. All unit and integration tests pass. No existing test regresses. No new flake.

---

## Risks

| Risk | Likelihood | Severity | Mitigation |
|---|---|---|---|
| **`WFSharedContext` holds non-serializable values** | Medium | High | Verified in Wave 0 before any engine code touches. If it holds lambdas/thread-bound state, Wave 0 escalates and the cycle scope revisits. |
| **`UserAndRolesAuthContext` holds live Spring Security session refs** | Medium | High | Verified in Wave 0. Mitigation options: a synthetic auth context reconstructed from persisted user id + roles, OR re-authentication on resume (undesirable). |
| **Jackson polymorphic subtype registration across plugin module boundaries** | Medium | Medium | Prototyped in Wave 0. Fallback: hardcode built-in subtypes in core with clear "third-party plugins need a core patch" documentation. |
| **Plugin classloader visibility on resume node** | Low | Medium | Operator responsibility: keep plugin parity across cluster nodes. Walkthrough G29 resolution: release claim, let another node try, exhaust after N attempts with clear error. |
| **`ExecutionJob` / Quartz coupling blocks `ExecutionTerminalWriteService` factoring** | Medium | High | Wave 4 spends dedicated effort reading `ExecutionJob.saveState` end-to-end before starting the factoring. Blocker surfaces early. |
| **Third-party streaming log writer plugins deployed in the wild without suspend SPI** | Certain | Low | Detected at suspend time, fail fast with clear error. Document the SPI addition for plugin authors in release notes. |
| **`BaseWorkflowExecutor` has hidden event-firing path that leaks `finishWorkflowExecution` on suspended results** | Medium | Medium | Walk event-firing code during Wave 1; mock listener assertions. |
| **NFS attribute caching delays log file size visibility on resume node** | Low | Low | Single-writer invariant + DB happens-before preserves correctness. Document `actimeo=0` / `noac` mount recommendation for NFSv3 operators. NFSv4 close-to-open consistency is sufficient by default. |
| **Transactional suspend half-commits (log flushed but DB rolled back, or vice versa)** | Low | Low | Flush + fsync log first (I5 step 2), then DB commit (step 6). Crash window leaves execution `running` with footerless log — reaped normally, no data loss. |
| **Checkpoint serialization misses a field a plugin relies on** | Medium | Medium | `CheckpointableContextComponent` marker interface; `SuspensionPolicy` fails fast at suspend time if a non-durable component is present. |
| **Waiting execution blocks a parent workflow's `.join()` indefinitely (child suspends)** | Certain until v2 | Medium | Wave 0 `SuspensionPolicy` forbids suspend inside sub-workflows. Clear error at suspend time. |
| **Rapid `running → waiting → running` oscillation confuses UI / metrics / reaper** | Low | Low | Integration test in Wave 4 exercises oscillation. `last_resumed_at` column prevents reaper false-orphan detection. |
| **Abort during suspend commit race** | Low | Low | Integration test in Wave 4 exercises concurrent abort + suspend-commit. Both outcomes (waiting-then-aborted vs. running-then-aborted) are acceptable as long as final state is consistent. |
| **Async log upload plugin (`ExecutionFileStoragePlugin`) fires prematurely on suspended execution** | Low | Medium | Verified pre-Wave 3 (10-minute read of `LogFileStorageService` upload trigger path). Expected: gated on `dateCompleted`, which suspend does not set. |
| **Cycle scope creep from the first-class confirm plugin** | Medium | Medium | Confirm plugin is a dedicated wave (Wave 5) with its own spec. Scope is bounded by that spec's §13 not-in-scope list. Discipline required to defer quorum, custom decision sets, notification UI, etc. to future cycles. |
| **`PreNextStepHook` fires in the hot path** | Medium | Medium | Hook is called between every pair of steps. A slow or buggy hook implementation impacts every execution, not just paused ones. Mitigation: contract clause "hooks MUST be cheap (≤1 DB read)"; `OperatorPauseHook` implements this with a single-column read or cached reference. Performance regression test in Wave 6 verifies non-paused execution path does not regress measurably. |
| **`pause_requested` flag visibility delay between nodes** | Low | Low | API write on node B commits to DB; node A's engine must read the committed value at the next boundary. DB read-your-writes consistency guarantees this within one transaction. Between-node delay bounded by one boundary check (≤ one step's duration). Acceptable operator latency. |
| **Single-step / final-step pause rejection** | Low | Low | API pre-check requires knowing "current step index" and "total step count" at pause time. Implementation reads from in-memory execution reference or DB; adds a small latency to `POST /pause`. Mitigation: document as normal, tolerate the latency. |
| **Pause-request reason lost on crash** | Low | Low | Reason is kept in memory between API call and synthesis, not in the DB. If the JVM crashes between `POST /pause` and the next boundary, the flag is set but the reason is null. Synthesis proceeds with `reason: null`. Documented edge case in `operator-pause.md §5`. |
| **Operator-pause ACL confusion vs. kill ACL** | Medium | Low | `pause` and `kill` are distinct ACL actions with distinct semantics. Default policy adds `pause` to admin only; operators must explicitly grant. Documentation must highlight: `kill` = immediate termination, `pause` = graceful park with resume. |

---

## Open decisions

Most locked in `docs/specs/workflow-suspend-resume.md §12`. Remaining for resolution during or before implementation:

1. **Checkpoint blob size bound.** No bound enforced in spec. Proposal: soft warn at 1MB, hard reject above 10MB with clear `SuspendRequest too large` error. Decision during Wave 2.
2. **Plugin configuration schema location for Jackson registration.** Whether confirm plugin lives in a dedicated gradle module or inlines in existing core-plugins. Decision during Wave 5.
3. **Comment persistence in log stream.** `confirm-workflow-step.md §15 Q1`: whether the confirmer's comment is written to the execution log stream in addition to the `ExecutionConfirmation` audit row. Decision during Wave 5.
4. **Role match semantics for `requiredConfirmerRoles`.** `confirm-workflow-step.md §15 Q3`: ANY (one-of) or ALL (all-of). Leaning ANY. Decision during Wave 5.
5. **UI rendering framework for minimum confirm affordance.** Whether to add to existing GSP-based execution detail page or kicker to a new Vue component (if Rundeck's UI is mid-migration). Decision during Wave 5.

---

## Decisions locked

Locked in `docs/specs/workflow-suspend-resume.md §12` (numbered 1–19). Reproduced here for cycle-manifest self-containment:

1. Return-based suspend API (`return context.suspend(request)`).
2. `context.getResumePayload()` accessor for resume payload.
3. `context.getSuspendMetadata()` accessor for frozen metadata.
4. `EngineWorkflowExecutor.executeWorkflowResume` as distinct method.
5. Jackson JSON checkpoint serialization.
6. `CheckpointableContextComponent` naming (not `DurableContextComponent`).
7. 2000ms default resume poll interval.
8. 30000ms default timeout sweep interval.
9. Lazy `SuspensionPolicy` validation at `context.suspend()` call time.
10. **Mandatory `ExecutionTerminalWriteService` factoring.** Not optional.
11. Checkpoint `version` field, v1 initial.
12. Jackson-serializable step result data contract.
13. `ExecutionResumeService` is a Grails service, not core.
14. **Plugin configuration frozen at suspend time** in `suspend_metadata`.
15. **Claim release + retry on resume-open failure**, exhausted after `maxAttempts` (default 3).
16. **Polymorphic `ResumePayload` interface** with `getType()` discriminator.
17. `findWaitingExecutions` as a distinct query on `ExecutionService`.
18. **Default methods on existing `StepExecutionContext` interface**, not a sub-interface.
19. **Notification delivery node semantics:** `waiting-*` from suspend-persisting node; completion events from terminal-writing node.

Plus confirm-plugin-specific decisions from `docs/specs/confirm-workflow-step.md` (approve/deny decision set for v1, ANY role semantics pending §15 Q3, body-field decision encoding, etc.).

---

## Implementation order notes

- **Wave 0 and Wave 1 can proceed in parallel** after Wave 0's Jackson prototype is validated. Wave 0 touches only new files + default-method additions; Wave 1 touches engine code that consumes Wave 0's types.
- **Wave 2 blocks on Wave 1** for `WorkflowExecutionServiceThread.isSuspended()` to have meaning.
- **Wave 3 can proceed in parallel with Wave 2** — they touch disjoint files (log writer vs. execution service).
- **Wave 4 blocks on Waves 2 + 3** — the resume worker needs schema + log writer SPI to function.
- **Wave 5 blocks on Wave 4** — the confirm plugin needs a functioning resume path to test against.
- **Wave 6 blocks on Wave 4** — the operator-pause feature needs the `PreNextStepHook` mechanism. Waves 5 and 6 can ship in parallel (they touch disjoint files).
- **Wave 7 is optional** and can happen any time after Waves 5 and 6 ship.

Target one PR per wave. Wave 1 is the most contentious review (core engine change). Wave 4 is the largest by LoC but most mechanical once the factoring is settled. Wave 5 is the widest surface (plugin + controller + ACL + domain + migration + UI) but each sub-piece is independently testable. Wave 6 is smaller than Wave 5 but reuses the same patterns (controller + ACL + UI + notification) making it quick to review once Wave 5 is merged.

---

## Artifact linkage

- **Audit:** `docs/audits/workflow-suspend-resume.md` — Phase 1 research across three rounds.
- **Walkthroughs:** `docs/walkthroughs/workflow-suspend-resume.md` — four scenarios traced, 36 gaps resolved.
- **Spec (primitive):** `docs/specs/workflow-suspend-resume.md` — revision 2 with all walkthrough amendments applied.
- **Spec (consumer — confirm plugin):** `docs/specs/confirm-workflow-step.md` — first-class built-in confirm plugin.
- **Spec (consumer — operator pause):** `docs/specs/operator-pause.md` — first-class operator-initiated pause/resume feature.
- **Test plan:** `docs/specs/test-plan-workflow-suspend-resume.md` — consolidated test methodology + success criteria.
- **This manifest:** wave sequence + exit criteria + risks + decisions. Becomes historical after the work ships.

---

**Phase 2 review pause:** no Phase 3 work begins until the user has reviewed all five artifacts. Wave 0's verification tasks (WFSharedContext, authContext, Jackson subtype registration) are permitted to begin after approval, as they are pure read/prototype work and surface blockers early.
