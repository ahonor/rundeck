# spec/workflow-suspend-resume

**Phase:** 2 (Spec) — Phase 2 output per `spec-cycle.md` practice.
**Status:** Draft with walkthrough amendments applied (36 gaps, revision 2). Pending Phase 2 review.
**Owned surface:** The lifecycle transition of a workflow execution into and out of the `waiting` state.
**Predecessor:** `docs/audits/workflow-suspend-resume.md` (Phase 1 output)
**Walkthroughs:** `docs/walkthroughs/workflow-suspend-resume.md` (normative, gaps enumerated)
**Companions:**
- `docs/specs/confirm-workflow-step.md` — first-class consumer plugin (plugin-initiated suspension)
- `docs/specs/operator-pause.md` — first-class consumer feature (operator-initiated suspension at step boundaries)
**Successor:** `docs/cycles/workflow-suspend-resume.md` (wave sequence + exit criteria)

This document declares what the suspend/resume primitive *is*. It is enduring: once the feature ships, this document continues to describe the feature's structure, contracts, and invariants for future readers. It deliberately does not describe implementation order, migration strategy, or rollout — those live in the cycle manifest.

---

## 1. What the area owns

The suspend/resume primitive owns the lifecycle transition of a workflow execution into and out of the `waiting` state — a new non-terminal execution status in which the execution has released its JVM thread and is persisted in the database, pending delivery of an external event.

It does not own:
- The policy that decides when an execution *should* suspend (that lives in step plugins for plugin-initiated suspension, or in `ApiOperatorPauseController` for operator-initiated suspension; see `operator-pause.md`).
- The specific payload shapes of resume events (the primitive defines a polymorphic `ResumePayload` interface; concrete subtypes are defined by consumers — the built-in `ConfirmWorkflowStep` defines `ConfirmationPayload` in `confirm-workflow-step.md`).
- The mechanism that delivers resume events (that lives in consumer plugins' API controllers; this primitive provides `ExecutionResumeService.markResumeReady` as a service method).
- The storage of log files across suspend/resume (that lives in the shared-filesystem layer; this primitive adds an SPI extension to the log writer).
- The cluster membership model (it is cluster-mode-aware but does not introduce a heartbeat or liveness registry).
- The UI surface for specific suspension types (the first-class confirm plugin defines its own minimum UI affordance in its companion spec).

---

## 2. Vocabulary

### 2.1 New types

| Type | Layer | Purpose |
|---|---|---|
| `SuspendRequest` | core (java) | Immutable serializable value describing a suspension: `token`, `payload` (plugin-internal opaque), `metadata` (framework-visible structured data), `timeoutMs`, `reason`, `waitingFor`. What a step plugin constructs when it wants to park. |
| `ResumePayload` | core (java) | **Polymorphic interface** with a `String getType()` discriminator. Represents the data delivered by the event that ends a suspension. Built-in subtypes: `ConfirmationPayload` (in the confirm plugin module). Third-party plugins may register additional subtypes via Jackson. |
| `SuspendedStepResult` | core (java) | `StepExecutionResult` implementation returned by `context.suspend(...)`. Engine-internal marker; not constructed by plugin authors directly. |
| `CheckpointableContextComponent` | core (java) | Marker interface. `ContextComponent<?>` entries that implement this may live in `ExecutionContextImpl.componentList` across a suspension. Plain `ContextComponent` without the marker makes the workflow unsuspendable. |
| `CheckpointableStreamingLogWriter` | core (java) | SPI extension on `StreamingLogWriter` adding `suspend()` (flush + **fsync** + close without writing footer). Writers that do not implement it make the workflow unsuspendable. |
| `SuspensionPolicy` | core (java) | Validates that a given `ExecutionContextImpl` + strategy + configured log writer permit suspension. Invoked lazily at `context.suspend(...)` call time. |
| `SuspensionNotAllowedException` | core (java) | Checked exception thrown when `SuspensionPolicy` rejects a suspension attempt. Propagates as a step failure with a clear reason. |
| `ExecutionCheckpoint` | grails (groovy) | Logical representation of the JSON blob stored in `Execution.checkpoint_data`. Holds the serializable subset of `ExecutionContextImpl` plus engine state (step index, completed step results). |
| `ExecutionResumeService` | grails (groovy) | Owns polling for resumable executions, atomic claim, context rehydration, engine re-entry. Also provides `markResumeReady` API for consumer plugins. |
| `ExecutionTerminalWriteService` | grails (groovy) | **New, mandatory factoring.** Owns the terminal-write path: setting `dateCompleted`, firing completion notifications, writing the log footer, clearing suspend-related columns. Invoked symmetrically from `ExecutionJob` (original-start path) and `ExecutionResumeService.resumeExecution` (resume path). |

### 2.2 New execution state

| State | Meaning | Transitions in | Transitions out |
|---|---|---|---|
| `waiting` | Execution is persisted, thread released, log file footerless, `dateCompleted` null, `serverNodeUUID` NULL. `wait_started_at` and `wait_timeout_at` set. `resume_ready` flag gates eligibility for the resume worker. | From `running` only, via step calling `context.suspend(...)` | To `running` via resume worker claim (thread re-spawned, engine re-entered). To `timedout` via timeout sweep. To `aborted` via abort path. To `failed` via resume-open exhaustion (see §10). |

### 2.3 New column semantics on `Execution`

| Column | Type | Semantics |
|---|---|---|
| `checkpoint_data` | CLOB, nullable | JSON blob of `ExecutionCheckpoint`. Populated while `status='waiting'`. Cleared on transition to terminal state (I11). |
| `suspend_metadata` | CLOB, nullable | JSON blob of `SuspendRequest.metadata`. Framework-visible structured data (confirmation message, required roles, decision set, timeout action, etc.). Populated at suspend time, read by consumer API controllers (e.g., `ApiConfirmController`), cleared on terminal transition (I11). |
| `wait_started_at` | TIMESTAMP, nullable | Wall-clock time of the *most recent* suspension entry. Distinct from `dateStarted` (never changes across resumes). |
| `wait_timeout_at` | TIMESTAMP, nullable | Absolute timeout deadline: `wait_started_at + SuspendRequest.timeoutMs`. Indexed for efficient timeout sweeps. |
| `resume_ready` | BOOLEAN, default false | Set to true by `ExecutionResumeService.markResumeReady` when an approval/event arrives. Worker only claims rows where `resume_ready = true`. |
| `resume_payload` | CLOB, nullable | JSON blob of `ResumePayload` (polymorphic; concrete subtype per `type` discriminator). Populated when `resume_ready` is set. |
| `last_resumed_at` | TIMESTAMP, nullable | Wall-clock time of the most recent successful resume claim. Used by the reaper to avoid mistaking a recently-resumed row for a stale running execution (walkthrough gap G17). |
| `resume_attempt_count` | INTEGER, default 0 | Incremented on each failed resume attempt (context rehydration failure, log open failure, plugin class not loadable). After N attempts (default 3, configurable), execution is marked `failed` with a clear error. |
| `pause_requested` | BOOLEAN, default false | Set by `ApiOperatorPauseController.pause` to request that the engine synthesize a suspension at the next step boundary. Read by the engine's boundary-hook check (§6.3). Cleared atomically when the engine acts on it, OR on terminal transition per I11. See `operator-pause.md` for the consumer that owns this column. |

---

## 3. Lifecycle state machine

Today:

```
queued → running → {succeeded | failed | aborted | timedout}
```

After this spec:

```
queued → running → {succeeded | failed | aborted | timedout}
              ↓                       ↑
           waiting → (claim+resume) → running → ...
              ↓
         {timedout | aborted | failed(resume-exhausted)}
```

An execution may cycle `running → waiting → running` multiple times (step 2 suspends, resumes, succeeds, step 4 suspends, resumes, etc.). Each cycle updates `wait_started_at` / `wait_timeout_at` / `last_resumed_at`.

**State machine invariants:**

- `dateStarted` is set exactly once, at original start. Never updated on resume.
- `dateCompleted` is set exactly once, at true completion (transition to a terminal state). Never set on entry to `waiting`.
- `wait_started_at` is overwritten each time the execution enters `waiting`.
- `last_resumed_at` is set each time a resume worker successfully claims the row. The reaper uses `GREATEST(dateStarted, COALESCE(last_resumed_at, dateStarted))` as the staleness basis.
- `serverNodeUUID` holds the currently-executing node's UUID while running, and is NULL while waiting.
- Completion notifications fire exactly once per execution, at the transition to a terminal state. Never on entry to or exit from `waiting`.
- The log file has exactly one `^text/x-rundeck-log-v2.0^` header (written at original start) and exactly one `^END^` footer (written at true completion). A waiting execution's log file is footerless; the reader path already tolerates this.

---

## 4. Invariants

Normative. Must hold at all times across all failure modes.

### I1 — Single writer

At any moment, at most one process on at most one cluster node holds an open write handle to a given execution's log file.

**Enforced by:** the atomic claim (I3). Node B cannot open a writer for a waiting execution until it has successfully updated the DB row to `serverNodeUUID = <B's UUID>`. Node A must have already flushed, fsync'd, and closed its writer before committing the transaction that set `serverNodeUUID = NULL` (suspend ordering, I5).

### I2 — Claim atomicity

For a given waiting execution, exactly one resume worker across the cluster successfully claims it. Losers of the claim race observe zero affected rows and back off without side effects.

**Enforced by:** the claim UPDATE's WHERE clause requires `serverNodeUUID IS NULL AND status = 'waiting' AND resume_ready = true`. The first committed UPDATE sets it non-null; all others see zero rows. DB serializability is sufficient; no distributed lock required.

### I3 — Checkpoint completeness

Any state a step plugin author can legitimately rely on across a suspension is either (a) in the `checkpoint_data` blob, (b) in the `suspend_metadata` blob, or (c) reconstructible from the project + framework on the resume node.

**Enforced by:** `SuspensionPolicy` rejects workflows whose context contains `ContextComponent<?>` entries that do not implement `CheckpointableContextComponent`. The data-only subset of `ExecutionContextImpl` is serialized to JSON. Live service references are rebuilt from the resume node's Grails context.

### I4 — Single terminal write

`dateCompleted`, terminal status string, completion notifications, and the log `^END^` footer are written/fired exactly once per execution, at the transition to a terminal state. Never on entry to or exit from `waiting`.

**Enforced by:** `ExecutionTerminalWriteService` is the sole writer of terminal state. Both `ExecutionJob` (original path) and `ExecutionResumeService` (resume path) invoke it. `ExecutionJob.saveState` and the resume worker's post-completion path both defer to the service rather than inlining terminal writes.

### I5 — Suspend ordering

On suspend, the order of operations inside `ExecutionService.onWorkflowSuspended` is:

1. Flush buffered log events to the writer.
2. Call `CheckpointableStreamingLogWriter.suspend()` — flushes, fsyncs data to disk, closes the underlying stream without writing the `^END^` footer.
3. Open a DB transaction.
4. Serialize context + engine state into `checkpoint_data`; serialize suspend metadata into `suspend_metadata`.
5. Set `status='waiting'`, `serverNodeUUID=NULL`, `wait_started_at=now()`, `wait_timeout_at=now()+timeoutMs`, `resume_ready=false`, `resume_payload=null`.
6. Commit transaction.
7. Fire `execution.waiting-<event-type>` notification (where event-type is read from `suspend_metadata.type`; for confirmation suspensions, `waiting-confirmation`).
8. Execution thread returns from `run()`.

A crash before step 6 leaves the execution in `status='running'` with a footerless log file. The reaper's existing orphan-cleanup path handles this: the execution is marked `incomplete`, no data is lost beyond the in-memory step-in-progress work. A crash after step 6 leaves the execution in `status='waiting'`, which is the intended outcome.

### I6 — Resume ordering

On resume, the order of operations inside `ExecutionResumeService.resumeExecution` is:

1. Atomic claim: `UPDATE execution SET serverNodeUUID=<self>, status='running', last_resumed_at=now() WHERE id=:id AND status='waiting' AND resume_ready=true AND serverNodeUUID IS NULL`.
2. If zero rows affected, skip (another node won the race).
3. Load `checkpoint_data`, `suspend_metadata`, `resume_payload` from the now-owned row.
4. Rehydrate `ExecutionContextImpl` from checkpoint + local framework.
5. Open log writer in resume mode via `CheckpointableStreamingLogWriterFactory.openForResume(execution)`.
6. Spawn new `WorkflowExecutionServiceThread` with rehydrated context + resume payload.
7. Thread enters `EngineWorkflowExecutor.executeWorkflowResume(context, item, checkpoint, resumePayload)`.
8. Engine primes `MutableStateObj` from checkpoint's completed-step-results, then re-invokes the suspended step with the resume payload accessible via `context.getResumePayload()` and frozen metadata via `context.getSuspendMetadata()`.

A crash between steps 1 and 6 leaves the row `status='running'`, `serverNodeUUID=<crasher>`, `last_resumed_at=<now>`. The reaper detects this as a stale running row (its `last_resumed_at < before`) and marks it incomplete — acceptable in v1, at the cost of losing that resume attempt. The approval event is still persisted in `ExecutionConfirmation` (or equivalent audit table), so operators have a record even if the execution itself was lost.

**Resume-attempt retry:** if steps 3–5 fail (context deserialization, log open failure, plugin class not loadable), the worker RELEASES the claim (`UPDATE execution SET serverNodeUUID=NULL, resume_attempt_count = resume_attempt_count + 1 WHERE id=:id`) and the next worker tick retries. After `resume_attempt_count >= <max-attempts>` (default 3), the worker marks the execution `failed` with `ResumeExhausted` reason instead of releasing. See §10.

### I7 — No suspended sub-workflows

A workflow executing as a child of another workflow (Job Reference step) may not suspend. `SuspensionPolicy` detects nested invocation via the execution context and rejects.

**Rationale:** cascade propagation through the parent's blocking `.join()` in `runRefJobWithTimer` requires additional machinery. Out of scope for v1. Deferred to a future cycle.

### I8 — No parallel-strategy suspension

A workflow running under `ParallelWorkflowStrategy` may not suspend. `SuspensionPolicy` detects the strategy and rejects.

**Rationale:** clean suspension under concurrent in-flight steps requires a quiesce protocol. Out of scope for v1. Deferred to a future cycle.

### I9 — Abort of a waiting execution bypasses Quartz

`ExecutionService.abortExecutionDirect` detects `status='waiting'` and marks the row aborted directly, without attempting `findExecutingQuartzJob`. Log writer is reopened in resume mode and closed normally (footer written via `ExecutionTerminalWriteService`). Completion notifications fire.

### I10 — No file-descriptor leakage across suspend window

Between `suspend()` returning on node A and `openForResume()` returning on node B (possibly the same node), no process on any cluster node holds an open write handle to the execution's log file.

**Enforced by:** `CheckpointableStreamingLogWriter.suspend()` closes the underlying `FileOutputStream` synchronously before returning. The DB transaction that clears `serverNodeUUID` cannot commit before `suspend()` returns (I5 step 6 follows step 2). Readers of the log file (live tail) open and close their handles per-request per `FSStreamingLogReader` existing behavior.

### I11 — Clear on terminal

On transition from `waiting` (or `running` post-resume) to any terminal state (`succeeded`, `failed`, `aborted`, `timedout`), the following columns are cleared atomically with the terminal write:
- `checkpoint_data = NULL`
- `suspend_metadata = NULL`
- `resume_ready = false`
- `resume_payload = NULL`
- `wait_started_at = NULL`
- `wait_timeout_at = NULL`
- `last_resumed_at = NULL`
- `resume_attempt_count = 0`
- `pause_requested = false`

`ExecutionConfirmation` rows (and equivalents for other first-class consumer audit tables) are NOT cleared — they are permanent audit trail.

**Enforced by:** `ExecutionTerminalWriteService.writeTerminal(execution, outcome)` performs all clearing in the same transaction that writes `dateCompleted` + final status.

---

## 5. SPI contracts

### 5.1 Step plugin SPI

`StepExecutionContext` gains three new methods with default implementations:

```java
public interface StepExecutionContext extends ExecutionContext {
    // existing methods unchanged

    /**
     * Returns the resume payload if this invocation is a resume of a previously
     * suspended step. Returns null on first invocation. The concrete subtype
     * (e.g., ConfirmationPayload) is determined by the ResumePayload.getType()
     * discriminator; plugins downcast after checking getType().
     */
    default ResumePayload getResumePayload() {
        return null;
    }

    /**
     * Returns the frozen suspend metadata for this execution. Populated on
     * resume invocations from the Execution.suspend_metadata DB column.
     * Returns an empty map on first invocation.
     *
     * Plugins MUST read configuration from this map on resume rather than
     * re-reading the job definition, to honor the configuration-freeze
     * contract (walkthrough gap G30, spec §12 decision 14).
     */
    default Map<String, Object> getSuspendMetadata() {
        return Collections.emptyMap();
    }

    /**
     * Request that this execution suspend, releasing its thread, until the
     * external event described by the request is delivered.
     *
     * @throws SuspensionNotAllowedException if the workflow is not suspendable
     *         (parallel strategy, sub-workflow, non-durable components, or
     *         non-checkpointable log writer).
     */
    default StepExecutionResult suspend(SuspendRequest request) {
        throw new UnsupportedOperationException(
            "This execution context does not support suspension"
        );
    }
}
```

**Canonical step plugin pattern:**

```java
public StepExecutionResult executeWorkflowStep(
    StepExecutionContext context,
    StepExecutionItem item
) throws StepException {

    ResumePayload payload = context.getResumePayload();

    if (payload == null) {
        // First invocation: request suspension.
        return context.suspend(SuspendRequest.builder()
            .token(generateToken())
            .reason("awaiting approval")
            .waitingFor("human:alice")
            .timeoutMs(TimeUnit.HOURS.toMillis(24))
            .metadata(Map.of(
                "type", "confirmation",
                "message", resolvedMessage,
                "decisionSet", List.of("approve", "deny")
            ))
            .payload(Map.of(/* plugin-internal opaque state */))
            .build());
    }

    // Resume invocation: the event has been delivered.
    // Use frozen metadata, NOT item.getProperties() — configuration freeze (§12 decision 14).
    Map<String, Object> frozen = context.getSuspendMetadata();
    // ...process payload using frozen config...
}
```

**Contract clauses:**

1. The step's `execute` method is called exactly twice for a successful suspended step: once to request suspension, once to process the resume payload. Multiple resume cycles per step are possible (a step may call `suspend` a second time on its resume invocation).
2. **The step plugin instance is NOT preserved across the two calls.** A fresh instance may be constructed, on a possibly-different node, with a possibly-different JVM, under a possibly-different plugin loader. Any state the step wants to carry across the suspension must be encoded in `SuspendRequest.payload` / `SuspendRequest.metadata` (serialized), via the `DataContext` / `SharedOutputContext` (serialized as part of the checkpoint), or in external storage.
3. **Execution listener state is NOT preserved across suspend.** Listeners that hold in-memory state across `begin`/`end` callback pairs must tolerate suspension or opt out via `SuspensionPolicy` by contributing a non-`CheckpointableContextComponent` to the context.
4. The step MUST return the result of `context.suspend(request)` directly, unmodified. Wrapping or transforming it loses the suspend signal.
5. The step MUST NOT log, emit side effects, or perform I/O after calling `context.suspend(...)`. Any such work happens during the resume invocation or prior to the suspend call.
6. The step MAY call `context.suspend(...)` multiple times across successive resumes (e.g., a multi-phase confirmation).
7. On resume, the step MUST read frozen configuration from `context.getSuspendMetadata()`, NOT from `item.getProperties()` / job definition. This enforces configuration freeze (§12 decision 14): a mid-flight edit to the job definition does not retroactively change resumed behavior.

### 5.2 Log writer SPI

`StreamingLogWriter` is unchanged. A new interface extends it:

```java
public interface CheckpointableStreamingLogWriter extends StreamingLogWriter {
    /**
     * Flush any buffered events, fsync to disk, and close the underlying stream
     * WITHOUT writing a terminal footer. The file remains in a state
     * indistinguishable from an in-progress execution; a subsequent
     * openForResume(...) may reopen it.
     *
     * Contract clauses:
     *   - MUST flush buffered in-JVM events before closing.
     *   - MUST fsync kernel buffers to disk before returning. Flush alone
     *     is insufficient — crashed processes may lose kernel-buffered
     *     writes that flush() reported as successful.
     *   - MUST NOT write a footer (no formatter.outputFinish() / equivalent).
     *   - After return, the writer instance is closed and must not be used.
     *   - Idempotent: a second call to suspend() on the same instance is a no-op.
     */
    void suspend();
}

public interface CheckpointableStreamingLogWriterFactory {
    /**
     * Open a writer in resume mode: append to the existing file at
     * execution.outputfilepath, skip writing the initial header.
     *
     * Precondition: any prior writer on this file has been suspend()ed,
     * and the DB state reflects serverNodeUUID transfer to the caller
     * (atomic claim succeeded).
     *
     * Postcondition: the returned writer behaves identically to a normal
     * writer for the remainder of the execution. Its close() writes the
     * terminal footer. This is the ONLY place the footer is written, and
     * only at true execution completion (I4).
     */
    StreamingLogWriter openForResume(Execution execution);
}
```

**Contract clauses:**

- `FSStreamingLogWriter` SHALL implement `CheckpointableStreamingLogWriter`.
- `LogFileStorageService` SHALL implement `CheckpointableStreamingLogWriterFactory.openForResume`.
- Third-party `StreamingLogWriter` plugins that do not implement `CheckpointableStreamingLogWriter` cause `SuspensionPolicy` to reject suspension attempts for workflows configured with such writers. The rejection message names the offending plugin.
- The `^text/x-rundeck-log-v2.0^` header is written exactly once per execution.
- The `^END^` footer is written exactly once per execution.

### 5.3 Context component durability SPI

`ContextComponent<?>` entries on `ExecutionContextImpl.componentList` may opt into suspension compatibility:

```java
public interface CheckpointableContextComponent extends ContextComponent<?> {
    /**
     * Serialize this component's state for inclusion in the execution checkpoint.
     * Returns a JSON-compatible value (String, Number, Boolean, Map, List, null).
     * Must round-trip through Jackson without loss.
     */
    Object checkpointState();

    /**
     * Rebuild a component from its serialized state on resume. Called on the
     * resume node with a fresh Grails / framework context.
     */
    void restoreState(Object state, IFramework framework);
}
```

`SuspensionPolicy` SHALL reject any workflow whose context contains a non-`CheckpointableContextComponent` entry. Component identity is not preserved across suspension; plugins that compare components by reference will break.

---

## 6. Engine contracts

### 6.1 Suspend path (engine side)

When a step returns `SuspendedStepResult`:

1. `WorkflowEngineOperationsProcessor` observes the completion on its state change queue, sets an internal `suspendRequested` flag, drains already-completed operations out of the queue (sequential strategy: none), and exits the loop at the next iteration boundary.
2. `WorkflowEngine.processOperations` returns. The return is a modified `WorkflowSystemState` carrying two new fields: `boolean suspended` and `List<SuspendRequest> suspendRequests` (typically one entry for sequential strategy).
3. `EngineWorkflowExecutor.executeWorkflowImpl` detects the suspended state during result aggregation (by inspecting `StepExecutionResult.isSuspended()` **before** `isSuccess()`), builds a `WorkflowExecutionResult` with `isSuspended() = true` and `workflowSuccess = false`. Suspended steps are NOT added to `stepFailures`.
4. `BaseWorkflowExecutor` detects the suspended result and **suppresses** the following listener callbacks:
   - `finishWorkflowExecution` — MUST NOT fire on suspension.
   - Step-level `finishWorkflowItem` / `finishExecuteNodeStep` for the suspended step DO fire, with the suspended result; listeners must check `result.isSuspended()` and handle accordingly. This gives listeners a chance to observe the suspension without being told the workflow ended.
5. `WorkflowExecutionServiceThread` stores the suspended result and returns from `run()`.
6. `ExecutionJob.executeCommand` detects `thread.isSuspended()` and routes to `executionService.onWorkflowSuspended(execmap, thread.result)` BEFORE invoking `finishExecution` or `saveState`.
7. `ExecutionService.onWorkflowSuspended` owns I5 steps 1–7 (log flush, writer suspend, DB transaction, notification).

### 6.2 Resume path (engine side)

`EngineWorkflowExecutor` gains a new method:

```java
public WorkflowExecutionResult executeWorkflowResume(
    StepExecutionContext context,
    WorkflowExecutionItem item,
    ExecutionCheckpoint checkpoint,
    ResumePayload resumePayload
);
```

Contract:

- Builds a `WorkflowSystem` identical to the original execution's initial construction.
- Primes `MutableStateObj` from `checkpoint.completedStepResults` (steps 0..N-1 are marked complete with their prior results).
- Sets `context.resumePayload` and `context.suspendMetadata` so the suspended step's re-invocation sees them.
- The processor loop re-invokes the step at `checkpoint.suspendedStepIndex`.
- After the resumed step returns a normal success/fail (or another `SuspendedStepResult`, producing a chained suspension), the loop continues normally through the remaining steps.
- Final completion writes the `^END^` footer via the writer's normal `close()` path and invokes `ExecutionTerminalWriteService.writeTerminal` (I4).

### 6.3 Engine boundary hook (for synthesized suspensions)

The engine provides an extension point for **synthesized suspensions** — suspensions requested from outside a step plugin, injected at step boundaries. Consumers (e.g., `operator-pause.md`) use this hook to park executions based on external state (e.g., a DB flag set by an API call).

**Hook location:** after each step's result is processed in `EngineWorkflowExecutor`'s aggregation loop (~`:272-293`) and BEFORE the next step operation begins, the engine invokes a pluggable `PreNextStepHook` interface. Hooks may return one of:

- `ProceedToNextStep` — default; no intervention.
- `SynthesizeSuspension(SuspendRequest)` — engine treats the result as if the just-completed step had returned a `SuspendedStepResult` with the given request. Normal suspend path (§6.1) ensues.

**Contract for hook implementations:**

- Hooks MUST be cheap (≤ 1 DB read or in-memory check). Called between every pair of steps.
- Hooks MUST be idempotent — a hook that fires at boundary N/N+1 must not produce a different result at N+1/N+2 if underlying state is unchanged.
- Hooks MUST NOT have side effects beyond reading state. The DB write (e.g., clearing `pause_requested`) is performed by the engine as part of the atomic suspend transaction, not by the hook.
- If a workflow has no more steps after the current one (last step has just returned), the hook is NOT invoked — there is no boundary at workflow termination.

**Registration:** hooks are registered as Spring/Grails services discovered at engine construction time. The built-in registration includes:

- `OperatorPauseHook` — reads `Execution.pause_requested`, synthesizes with `suspend_metadata.type = "operator-pause"`. See `operator-pause.md`.

Third-party hooks are not supported in v1 — only built-in hooks registered at engine-construction time. Dynamic hook registration deferred to a follow-up cycle if needed.

**Invariants preserved by synthesized suspensions:**

- I1–I11 of this spec apply to synthesized suspensions identically to plugin-initiated suspensions. The suspension goes through the same `onWorkflowSuspended` path, the same DB writes, the same log writer handling.
- `SuspensionPolicy` validation applies: a synthesized suspension under parallel strategy or in a sub-workflow is rejected at the policy check, same as plugin-initiated.
- Hook invocation does NOT fire workflow-end listener callbacks (I4).

---

## 7. Persistence model

### 7.1 Schema additions

```sql
ALTER TABLE rundeck_execution ADD COLUMN checkpoint_data      CLOB NULL;
ALTER TABLE rundeck_execution ADD COLUMN suspend_metadata     CLOB NULL;
ALTER TABLE rundeck_execution ADD COLUMN wait_started_at      TIMESTAMP NULL;
ALTER TABLE rundeck_execution ADD COLUMN wait_timeout_at      TIMESTAMP NULL;
ALTER TABLE rundeck_execution ADD COLUMN last_resumed_at      TIMESTAMP NULL;
ALTER TABLE rundeck_execution ADD COLUMN resume_ready         BOOLEAN DEFAULT FALSE NOT NULL;
ALTER TABLE rundeck_execution ADD COLUMN resume_payload       CLOB NULL;
ALTER TABLE rundeck_execution ADD COLUMN resume_attempt_count INTEGER DEFAULT 0 NOT NULL;
ALTER TABLE rundeck_execution ADD COLUMN pause_requested      BOOLEAN DEFAULT FALSE NOT NULL;
```

All new columns are nullable or have defaults. Forward-compatible.

Backward-compatible downgrade: dropping the columns does not affect rows whose `status` is not `waiting`. A downgrade while any row is `waiting` orphans those rows — document as an operator constraint.

The `ExecutionConfirmation` table defined in `confirm-workflow-step.md §7` is independent: it is not part of this primitive's schema but ships in the same cycle.

### 7.2 Indexes

```sql
CREATE INDEX idx_execution_waiting_claim
    ON rundeck_execution (status, resume_ready, server_node_uuid);

CREATE INDEX idx_execution_wait_timeout
    ON rundeck_execution (status, wait_timeout_at);
```

The first supports the atomic claim query. The second supports the timeout expiry sweep (filtered in application code to `status='waiting'`).

### 7.3 Checkpoint blob format

`checkpoint_data` JSON shape (Jackson-serialized):

```json
{
  "version": 1,
  "suspendedStepIndex": 2,
  "suspendRequest": { ... SuspendRequest fields (token, timeoutMs, reason, waitingFor, payload) ... },
  "contextData": {
    "dataContext":        { ... },
    "privateDataContext": { ... },
    "sharedDataContext":  { ... },
    "nodeSet":            { ... },
    "authContext":        { ... },
    "flowControl":        { ... },
    "outputContext":      { ... },
    "charsetEncoding":    "UTF-8",
    "user":               "alice"
  },
  "components": [
    { "class": "com.example.MyComponent", "state": ... }
  ],
  "completedStepResults": [
    { "stepIndex": 0, "success": true, "data": { ... } },
    { "stepIndex": 1, "success": true, "data": { ... } }
  ]
}
```

**Serialization contract:**

- `version` is a schema version. v1 initial. Readers reject unknown versions with a clear error.
- Unknown fields within a known version are ignored (`FAIL_ON_UNKNOWN_PROPERTIES=false`). Permits additive evolution within v1.
- `components` preserves ordering. Each entry's `class` is resolved via `Class.forName` on the resume node.
- `completedStepResults[i].data` MUST be Jackson-serializable. Step results holding live objects (`INodeEntry`, plugin-specific types) must flatten to primitives before checkpoint, OR register a Jackson module. Plugin authors that produce non-serializable step data are incompatible with suspension.

`suspend_metadata` is a separate column, a flat JSON object (not versioned; consumers are responsible for evolving their own schema within the blob).

---

## 8. Collaborators

### 8.1 New: `ExecutionResumeService` (Grails service)

```groovy
class ExecutionResumeService {
    // Periodic worker; tick interval from rundeck.execution.resume.pollIntervalMs (default 2000).
    @Scheduled(fixedDelayString = '${rundeck.execution.resume.pollIntervalMs:2000}')
    void scheduledResumePoll()

    // Timeout expiry sweep.
    @Scheduled(fixedDelayString = '${rundeck.execution.resume.timeoutSweepIntervalMs:30000}')
    void scheduledTimeoutSweep()

    /**
     * Called by consumer API controllers (e.g., ApiConfirmController) to deliver
     * a resume event.
     *
     * @return true if the row was updated. Returns false if the execution is
     *         no longer in 'waiting' state (aborted, already resumed, terminal).
     *         Callers must handle false by returning 409 Conflict to their own
     *         API caller.
     */
    boolean markResumeReady(Long execId, ResumePayload payload)

    /**
     * Called by the polling worker after a successful atomic claim.
     * Rehydrates context, opens log writer in resume mode, spawns thread,
     * enters EngineWorkflowExecutor.executeWorkflowResume.
     *
     * On rehydration/open failure, releases the claim and increments
     * resume_attempt_count, unless the count has reached max_attempts, in
     * which case marks the execution failed via ExecutionTerminalWriteService.
     */
    void resumeExecution(Execution execution)

    /** Operational visibility for UI / API. */
    List<Execution> findWaitingExecutions(String project = null)
}
```

### 8.2 New: `ExecutionTerminalWriteService` (Grails service, mandatory factoring)

Promoted from conditional to mandatory by walkthrough gap G31. Factors the terminal-write path out of `ExecutionJob.saveState` so the resume worker on any cluster node can invoke it symmetrically.

```groovy
class ExecutionTerminalWriteService {
    /**
     * Write the terminal state for an execution. Owns:
     *   - dateCompleted setting
     *   - final status transition
     *   - completion notifications (fired from this node)
     *   - log file footer write (via writer.close())
     *   - clearing suspend-related columns (I11)
     *
     * Invoked by ExecutionJob (original-start terminal path) AND
     * ExecutionResumeService (resume-completed terminal path).
     */
    void writeTerminal(Execution execution, WorkflowExecutionResult result)
}
```

`ExecutionJob.saveState` becomes a thin wrapper that delegates to this service when the result is not suspended. The resume worker invokes the same service directly.

### 8.3 Edits to `ExecutionService`

```groovy
// New constants
public static final String EXECUTION_WAITING = "waiting"

// New methods
void onWorkflowSuspended(Map execmap, WorkflowExecutionResult result)
StepExecutionContext buildExecutionContextFromCheckpoint(Execution e, ExecutionCheckpoint checkpoint)

// Modified
abortExecutionDirect(...)  // branch for status='waiting' bypasses Quartz lookup (I9)
```

### 8.4 Edits to `Execution` domain

```groovy
class Execution {
    // New fields (see §2.3)
    String  checkpointData
    String  suspendMetadata
    Date    waitStartedAt
    Date    waitTimeoutAt
    Date    lastResumedAt
    Boolean resumeReady = false
    String  resumePayload
    Integer resumeAttemptCount = 0

    static runningExecutionsCriteria = new DetachedCriteria<>(Execution).build {
        isNotNull('dateStarted')
        isNull('dateCompleted')
        ne('status', ExecutionService.EXECUTION_WAITING)  // exclude waiting
        // Reaper staleness check uses greatest(dateStarted, lastResumedAt):
        // implementation detail — enforced at query time, not in this criteria.
        or { ... existing predicate ... }
    }

    ExecutionState getExecutionState() {
        // ... existing logic ...
        if (status == ExecutionService.EXECUTION_WAITING) return ExecutionState.waiting
    }
}
```

### 8.5 Edits to `ExecutionJob`

```groovy
def executeCommand(...) {
    // ... existing thread.join loop unchanged ...

    // After loop exits, before finishExecution/saveState:
    if (thread.isSuspended()) {
        executionService.onWorkflowSuspended(execmap, thread.result)
        return new RunResult(suspended: true, execmap: execmap, result: thread.result)
    }

    // ... normal path delegates to ExecutionTerminalWriteService via saveState ...
}

def saveState(...) {
    if (runResult?.suspended) {
        // No terminal write. onWorkflowSuspended already persisted everything.
        return
    }
    executionTerminalWriteService.writeTerminal(execution, runResult.result)
}
```

### 8.6 Edits to `EngineWorkflowExecutor`

- `executeWorkflowImpl` recognizes `SuspendedStepResult` in the aggregation loop; produces a `WorkflowExecutionResult` with `isSuspended=true`.
- New method `executeWorkflowResume(...)` as §6.2.

### 8.7 Edits to `WorkflowEngineOperationsProcessor`

- New field `suspendRequested`.
- `processStep()` checks for `isSuspended()` on completions; sets the flag; exits at next iteration boundary.
- `WorkflowSystemState` gains `boolean suspended` and `List<SuspendRequest> suspendRequests`.

### 8.8 Edits to `FSStreamingLogWriter`

Implements `CheckpointableStreamingLogWriter`. The `suspend()` method:

```groovy
void suspend() {
    synchronized (this) {
        if (stream != null) {
            stream.flush()
            // fsync (I5 step 2, §5.2 contract): ensure kernel buffers written.
            if (stream instanceof FileOutputStream) {
                ((FileOutputStream) stream).getFD().sync()
            }
            stream.close()
            stream = null
            // Intentionally does NOT call formatter.outputFinish()
        }
    }
}
```

### 8.9 Edits to `LogFileStorageService`

New method:

```groovy
StreamingLogWriter getLogFileWriterForResume(Execution execution) {
    File file = getFileForExecutionFiletype(execution, LoggingService.LOG_FILE_FILETYPE, false)
    FileOutputStream stream = new FileOutputStream(file, true)  // append mode
    return new FSStreamingLogWriter(stream, formatter, ..., resumeMode: true)
    // resumeMode flag causes the writer to skip formatter.outputBegin()
}
```

### 8.10 Edits to `StepExecutionContext` (core interface)

See §5.1. Three default methods added: `getResumePayload()`, `getSuspendMetadata()`, `suspend(SuspendRequest)`.

---

## 9. Cluster semantics

### 9.1 Ownership model: unowned-on-suspend

A suspended execution has `serverNodeUUID = NULL`. It is not owned by any node. The atomic claim query transfers ownership to whichever node wins the race. No cluster membership, heartbeat, or liveness state is required.

### 9.2 Resume event delivery

Consumer plugins' API controllers (e.g., `ApiConfirmController`) may land on any cluster node via the load balancer. The controller writes `resume_ready=true` + `resume_payload` + any consumer-specific audit rows (e.g., `ExecutionConfirmation`) to the DB in a single transaction via `markResumeReady`. The controller does NOT directly invoke resume; the polling worker picks it up.

### 9.3 Polling worker dispatch

Each Rundeck node runs its own `ExecutionResumeService` bean with an independent `@Scheduled` tick. On each tick:

1. Query eligible rows.
2. For each candidate (ordered by `wait_started_at` ASC for fairness), attempt atomic claim.
3. On successful claim (1 row), invoke `resumeExecution(execution)` synchronously on the worker's thread.
4. On zero rows, skip and continue polling.
5. Resume proceeds: context rehydration, log writer reopen, thread spawn, engine re-entry.

Single-node deployment is a degenerate case: only one poller, claims always win trivially, no actual contention, but the machinery is identical (walkthrough gap G24).

### 9.4 Multi-node consistency requirements

**Shared filesystem MUST provide close-to-open consistency semantics.** NFSv4 provides this by default; NFSv3 requires mount options like `actimeo=0` or `noac`. Local filesystems trivially provide it. Operator documentation must state this requirement (walkthrough gap G26).

Rationale: node A flushes + fsyncs + closes the log file, commits the DB transaction. Node B reads the committed DB row, then opens the log file. CTO consistency guarantees node B's open sees all data written by node A's close.

### 9.5 Notification delivery node

**Suspension notifications** (`execution.waiting-<event-type>`) fire from the node that persisted the suspend — the node whose `onWorkflowSuspended` ran.

**Completion notifications** (`execution.succeeded` / `execution.failed` / `execution.aborted`) fire from the node that performed the terminal write — the node whose resume worker claimed the final resume (or the originating node, if the execution never suspended).

These may be different nodes for a given execution. Operators should not treat "which node fired the notification" as a stable signal. Notification consumers that care about node identity should inspect the event payload, which carries the firing node's UUID.

### 9.6 Pre-existing cluster limitation (unchanged)

Running (non-suspended) executions on a crashed node are NOT taken over by other nodes. They are marked `incomplete` when the crashed node restarts and runs its own orphan cleanup. This is existing Rundeck behavior; suspend/resume inherits it without change. **Waiting** executions, however, DO survive node crashes because they are unowned in the DB and any node's polling worker can claim them (walkthrough 4).

---

## 10. Error handling

### 10.1 Suspend-time errors

| Condition | Outcome |
|---|---|
| Parallel strategy | `SuspensionNotAllowedException("parallel strategy does not support suspension")`. Step fails. |
| Sub-workflow invocation | `SuspensionNotAllowedException("sub-workflows may not suspend")`. |
| Non-checkpointable context component present | `SuspensionNotAllowedException("component <class> is not checkpointable")`. |
| Configured log writer does not implement `CheckpointableStreamingLogWriter` | `SuspensionNotAllowedException("log writer <name> does not support suspension")`. |
| Checkpoint serialization failure (e.g., non-Jackson-compatible step data) | Suspend fails; step fails with `SuspensionFailedException`. Execution transitions to `failed`. |
| Log flush/fsync/close failure | Suspend fails; step fails with `SuspensionFailedException`. Execution transitions to `failed`. |
| DB commit failure during suspend | Transaction rolled back; execution remains `running`; step fails; normal failure path. |

### 10.2 Resume-time errors

| Condition | Outcome |
|---|---|
| Checkpoint schema version unknown | Resume fails; execution marked `failed` via `ExecutionTerminalWriteService`. Does NOT retry. |
| Context component class not loadable on resume node (plugin parity issue) | Resume worker RELEASES the claim and increments `resume_attempt_count`. If count < max, another worker will retry. If count ≥ max, marked `failed` with `ResumeExhausted`. |
| Step plugin class not loadable on resume node | Same as context component: release + retry up to max (walkthrough gap G29). |
| Log file missing on shared FS | Same as above: release + retry up to max (walkthrough gap G36). |
| Log file exists but cannot be opened in append mode (permission, NFS failure) | Same: release + retry up to max. |
| Checkpoint deserialization failure (corrupt blob) | Does NOT retry — the blob is structurally broken. Marked `failed` immediately. |
| Atomic claim returns zero rows (race lost) | Worker skips and continues polling. No error. |
| Atomic claim returns zero rows (row no longer waiting) | Worker skips and continues. No error. |
| Resume thread crashes before first step re-invocation (e.g., JVM OOM) | Execution row is `running` with `serverNodeUUID=<crasher>`. Reaper catches it via `last_resumed_at` staleness check and marks incomplete. Approval event persisted in consumer audit table is lost. |

**Max retry attempts (`resume_attempt_count`):** default 3, configurable via `rundeck.execution.resume.maxAttempts`. After exhaustion, the execution is marked `failed` with error reason `ResumeExhausted` and a message listing the last attempt's failure cause.

### 10.3 Timeout expiry

| Condition | Outcome |
|---|---|
| `wait_timeout_at <= now()` and `resume_ready = false` | Timeout sweep constructs a synthetic `ResumePayload` (subtype appropriate to `suspend_metadata.type`; for confirmation suspensions, a `ConfirmationPayload` with `timeout=true`), marks `resume_ready=true`, next polling tick resumes normally. The step observes `payload.isTimeout() == true` and handles per its configured `timeoutAction`. |

### 10.4 Abort

| Condition | Outcome |
|---|---|
| Abort requested on a `waiting` execution | `abortExecutionDirect` detects status, bypasses Quartz lookup (I9), reopens writer via `openForResume`, invokes `ExecutionTerminalWriteService.writeTerminal` with aborted outcome. Footer written normally. Completion notifications fire. Consumer audit tables (e.g., `ExecutionConfirmation`) preserved. |

---

## 11. Test strategy

### 11.1 Core unit tests

- `SuspendRequest` / `ResumePayload` polymorphic Jackson round-trip with discriminator.
- `SuspendedStepResult.isSuspended() == true`, `isSuccess() == false`.
- `SuspensionPolicy` rejects parallel, sub-workflow, non-checkpointable component, non-checkpointable writer.
- `StepExecutionResult.isSuspended()` default returns false; existing implementations unchanged.
- `WorkflowEngineOperationsProcessor` exits loop on suspended completion; returns partial results.
- `EngineWorkflowExecutor` aggregates suspended result with `isSuspended=true`, does NOT mark as failure.
- `EngineWorkflowExecutor.executeWorkflowResume` primes state from checkpoint, re-invokes suspended step, continues.
- `FSStreamingLogWriter.suspend()` closes without footer AND calls fsync; roundtrip test: open → write A → suspend → openForResume → write B → close → file has one header, events A then B, one footer, no duplication.
- Listener contract: `finishWorkflowExecution` does NOT fire on suspend; `finishWorkflowItem` DOES fire with `result.isSuspended()==true`.

### 11.2 Grails unit tests

- `Execution.runningExecutionsCriteria` excludes `waiting`.
- `Execution.getExecutionState()` maps `waiting` to `ExecutionState.waiting`.
- `ExecutionService.onWorkflowSuspended` leaves `dateCompleted=null`, clears `serverNodeUUID`, sets `waitStartedAt` + `waitTimeoutAt`, writes checkpoint + metadata, fires `waiting-<type>` notification.
- `ExecutionService.buildExecutionContextFromCheckpoint` reconstructs equivalent context.
- `ExecutionService.abortExecutionDirect` on `waiting` bypasses Quartz lookup and calls `ExecutionTerminalWriteService`.
- `ExecutionJob.saveState` with suspended `RunResult` skips `dateCompleted` and notifications (asserted against mock).
- `ExecutionTerminalWriteService.writeTerminal` sets `dateCompleted`, writes footer, fires completion notification, clears all suspend-related columns (I11), preserves `ExecutionConfirmation` rows.
- `ExecutionResumeService.markResumeReady` returns false if `status != 'waiting'`.
- `ExecutionResumeService.scheduledResumePoll` queries, claims atomically, invokes resume.
- `ExecutionResumeService.scheduledTimeoutSweep` finds expired rows and marks them resume-ready with synthetic timeout payload.
- Resume failure: simulated rehydration failure releases claim, increments `resume_attempt_count`.
- Resume exhaustion: `resume_attempt_count >= max` marks execution `failed`.

### 11.3 Integration tests

**All integration tests run in cluster-simulating mode** (walkthrough gap G25): every resume forces fresh context rehydration (not reusing in-memory objects), simulating cross-node behavior even on single-node CI.

- **Single-node suspend/resume:** mock suspendable step, full pipeline, assert end state per §11.2.
- **Two-node cluster:** tmpdir-backed shared FS + shared DB, suspend on node A, resume on node B. Assert log file written correctly by both nodes, no corruption.
- **Node failure while waiting:** node A dies mid-suspend-in-transit (fault-injected at each I5 step); verify recovery behavior at each injection point.
- **Node failure after waiting:** node A dies, node B claims and resumes successfully.
- **Node failure during resume:** node B claims, crashes before first step; reaper recovers on restart.
- **Concurrent claim race:** two workers attempt claim simultaneously; exactly one wins.
- **Timeout expiry:** suspended execution with short timeout, sweep fires, synthetic timeout payload delivered.
- **Abort during waiting:** abort request on waiting row, transitions directly to aborted.
- **Non-checkpointable component:** workflow with a non-durable component, `context.suspend` throws, clear error.
- **Abort-during-suspend race** (open question Q8): concurrency test where abort arrives while suspend transaction is committing. Both outcomes are acceptable as long as the final state is consistent (waiting then aborted, or running then aborted).
- **Rapid status oscillation** (open question Q5): step suspends, resumes, suspends again within 100ms; verify no UI / API / metric code crashes.

### 11.4 Contract tests for third-party implementers

- `CheckpointableStreamingLogWriter` abstract contract test (third-party writers pass their factory to the test harness).
- `CheckpointableContextComponent` abstract contract test.

---

## 12. Locked decisions

Carried forward from `docs/audits/workflow-suspend-resume.md §8`, refined here, and amended by walkthrough gap resolutions.

1. **Suspend API shape:** return-based. `context.suspend(request)` returns a `StepExecutionResult` sentinel; step author writes `return context.suspend(request)`.
2. **Resume payload accessor:** `context.getResumePayload()` on `StepExecutionContext`. Returns `ResumePayload` (polymorphic interface with `getType()` discriminator — see decision 16).
3. **Suspend metadata accessor:** `context.getSuspendMetadata()` on `StepExecutionContext`. Returns frozen `Map<String,Object>` of framework-visible metadata. Plugins MUST read configuration from this on resume, not from `item.getProperties()`.
4. **Resume entry point into engine:** new `EngineWorkflowExecutor.executeWorkflowResume` method distinct from `executeWorkflowImpl`.
5. **Checkpoint serialization format:** Jackson JSON.
6. **Naming of the context component marker interface:** `CheckpointableContextComponent`.
7. **Resume worker poll interval:** default 2000ms, configurable via `rundeck.execution.resume.pollIntervalMs`.
8. **Timeout sweep interval:** default 30000ms, configurable via `rundeck.execution.resume.timeoutSweepIntervalMs`.
9. **Suspension policy validation lifecycle:** lazy, at `context.suspend()` call time.
10. **Terminal-write factoring is MANDATORY** (promoted from conditional by walkthrough gap G31). New `ExecutionTerminalWriteService` owns all terminal writes. Both `ExecutionJob` and `ExecutionResumeService` invoke it symmetrically.
11. **Schema versioning for checkpoint blob:** `version` field, v1 initial.
12. **Component state format:** Jackson-serializable values.
13. **`ExecutionResumeService` is a Grails service**, not a core module.
14. **Plugin configuration is FROZEN at suspend time** (walkthrough gap G30). The plugin's resolved configuration is stored in `Execution.suspend_metadata` at suspend time. On resume, the plugin reads from frozen metadata, not the current job definition. Mid-flight edits to the job definition do not retroactively affect resumed behavior.
15. **Resume-open failures RELEASE THE CLAIM** (walkthrough gap G36). On context rehydration / log open / plugin class loading failure, the worker sets `serverNodeUUID=NULL` and increments `resume_attempt_count`. After `max_attempts` exhaustion (default 3), the worker marks the execution `failed` with `ResumeExhausted` reason.
16. **`ResumePayload` is a polymorphic interface** with `String getType()` discriminator. Built-in subtypes: `ConfirmationPayload`. Third-party plugins register subtypes via Jackson `@JsonTypeInfo` / module loading.
17. **`findWaitingExecutions` as a distinct query**: yes, on `ExecutionService`, for operational visibility.
18. **`StepExecutionContext` gains new methods as DEFAULT METHODS** on the existing interface (walkthrough open question Q1 resolved). Not a sub-interface.
19. **Notification delivery node** (walkthrough gap G32): `waiting-*` events fire from the suspend-persisting node; completion events fire from the node performing the terminal write. Consumers must not assume stable node identity.

---

## 13. Not in scope (v1)

- **Sub-workflow suspension cascade.** v1 rejects at `SuspensionPolicy`.
- **Parallel strategy suspension.** v1 rejects at `SuspensionPolicy`.
- **UI polish for waiting status beyond the first-class consumer plugin's minimum affordance** (defined in `confirm-workflow-step.md §11`). Dashboards, badge counts, waiting-execution list views, cross-execution inbox — all follow-up.
- **Metric enrichment.** Histograms, alerts, purpose-built dashboards — follow-up.
- **Distributed low-latency wake-up.** v1 uses DB polling (1–2s). In-process event wake-up is a follow-up optimization.
- **Third-party log writer plugin adaptation.** v1 ships with only `FSStreamingLogWriter` supporting suspend.
- **Schema migration of waiting executions across Rundeck version upgrades.** v1 documents as operator constraint.
- **Checkpoint encryption at rest.** Plaintext CLOB.
- **Resume scheduling priorities.** Oldest-first, no SLA-aware dispatch.
- **Multi-confirmer quorum / custom decision sets.** See `confirm-workflow-step.md §14`.
- **Notification plugin authoring for confirmation UX.** Event type `execution.waiting-confirmation` is IN scope as a minimal emission; plugins that consume it (email, Slack) rely on existing Rundeck notification plumbing. Authoring new notification plugins is not part of this cycle.

---

## 14. Open questions (remaining for Phase 2 review)

Resolved by walkthrough application: Q1 (default methods), Q4 (factoring mandatory), Q5 (oscillation — tested in §11.3), Q8 (abort-during-suspend — tested in §11.3).

Resolved by Wave 0 verification (branch `cycle/workflow-suspend-resume`):

1. **`WFSharedContext` serializability — RESOLVED: CONDITIONAL PASS.** The data itself (nested `Map<String, Map<String, String>>` in `BaseDataContext`) is JSON-safe. The friction point is that `ContextView` (a Lombok `@Value` type) is used as the `Map` key in `MultiDataContextImpl` (`core/.../data/MultiDataContextImpl.java:32`). Jackson serialization of `Map<ContextView, DataContext>` requires either (a) a custom `KeyDeserializer` for `ContextView`, (b) a flattened representation `Map<String, DataContext>` keyed by `ContextView.toString()`, or (c) `@JsonCreator` on `ContextView` to enable key parsing. **Decision:** use (b), flatten the map in the checkpoint JSON to avoid custom key deserializers. Implementation detail belongs in Wave 2 (checkpoint serialization); Wave 0 is unblocked.

2. **`UserAndRolesAuthContext` serializability — RESOLVED: PASS via existing factory.** `SubjectAuthContext` (`core/.../authorization/SubjectAuthContext.java:28-30`) holds a live `javax.security.auth.Subject` and a live `Authorization` object — neither directly serializable. However, `BaseAuthContextProvider.getAuthContextForUserAndRoles(String user, List<String> roles)` (`rundeckapp/src/main/groovy/org/rundeck/app/authorization/BaseAuthContextProvider.groovy:77-94`) already exists as a factory and is used by `ExecutionService.groovy:1557` today. **Strategy:** persist `{user, roles, urn}` in the checkpoint; on resume, call this factory to reconstruct a synthetic `SubjectAuthContext` with a fresh `Authorization` fetched from the local node's ACL engine. **Privilege drift decision:** FROZEN — the checkpoint persists suspend-time roles. Resumption uses those roles regardless of whether the user's current roles have changed. Consistent with existing Rundeck behavior (`Execution.userRoleList` is already persisted and reused for reruns) and with spec §12 decision 14 (frozen plugin config).

3. **Log file size bounds** — still open. A long-suspended execution may have its log tailed many times, each read reopening the file. Acceptable for v1; document in operator guide.

4. **Checkpoint blob size** — still open. Proposal: soft bound of 1MB, hard reject above 10MB with clear error. Decision deferred to Wave 2 implementation.

5. **Subtype registration path for `ResumePayload` — RESOLVED: Spring bean registration (Approach 2).** Approach 1 (ServiceLoader-discovered Jackson Module via `findAndRegisterModules()`) is **refuted** — Rundeck plugins are isolated in child `URLClassLoader`s (`core/.../plugins/JarPluginProviderLoader.java:876-893` uses `LocalFirstClassLoader`), and Jackson's `findAndRegisterModules` uses the thread context classloader which cannot see plugin classes. Approach 2 uses a new `JacksonSubtypeRegistrar` interface that plugin modules expose as Spring beans; the core `ObjectMapper` factory injects all registered beans at construction and calls `registerSubtypes(...)` on each. Spring bean instantiation happens in the plugin's context with correct classloader visibility. `RundeckPluginRegistry` (`rundeckapp/src/main/groovy/.../RundeckPluginRegistry.groovy:54-77`) already integrates plugins with the Grails `ApplicationContext`. **Fallback:** if Approach 2 runs into an unexpected Grails bean-discovery issue at Wave 0 implementation time, fall back to Approach 3 (hardcoded list in core) with built-in subtypes `ConfirmationPayload` and `OperatorResumePayload` enumerated at compile time.

---

**⏸ PAUSE. Present to user. Do not begin Phase 3 implementation until both this spec and `docs/specs/confirm-workflow-step.md` are reviewed.**
