# audit/workflow-suspend-resume

**Phase:** 1 (Audit) — Phase 1 output per `spec-cycle.md` practice.
**Scope:** Rundeck's workflow execution engine, with specific attention to the feasibility of introducing a suspend/resume primitive so long-waiting step plugins release their JVM thread. Target deployment includes a two-node cluster sharing a database and filesystem.
**Research rounds:** Three audits + one focused log-writer verification.
**Pause point:** Present to user before drafting Phase 2 spec at `docs/specs/workflow-suspend-resume.md`.
**Reference consumer:** `io.rx.rundeck.RxConfirmWorkflowStep` in `rx-rundeck-plugin`, which today polls the filesystem every 1s for approval/denial marker files (`RxLifecycleSimulator.java:191`) — pinning one OS thread per pending approval for the wait duration.

---

## 1. What the current engine requires of steps (implicit contract)

Rundeck has no written design document for workflow suspension because the feature does not exist. The implicit contract the engine imposes on step plugins today, inferred from the code, is:

1. **A step returns synchronously.** `ExecutionServiceImpl.executeStep()` (`core/src/main/java/com/dtolabs/rundeck/core/execution/ExecutionServiceImpl.java:84`) calls `executor.executeWorkflowStep(context, item)` (line 114) and blocks until it returns a `StepExecutionResult`. There is no continuation-passing, no promise, no callback.
2. **A step returns success or failure.** `StepExecutionResult` (`core/.../execution/workflow/steps/StepExecutionResult.java:38-44`) is a narrow interface: `getFailureData()`, `getFailureReason()`, `getFailureMessage()`, plus an inherited `isSuccess()`. No third state.
3. **A step runs on the execution's dedicated thread.** Each execution is backed by a `WorkflowExecutionServiceThread` spawned in `ExecutionService.groovy:1266` and started at `:1274`. Steps execute on that thread from start to finish. Thread-bound state (thread-locals, MDC, buffered log events) is available throughout.
4. **A step participates in a contiguous log stream.** Buffered log output from the step is flushed through `LoggingManager.runWith()` (`core/.../logging/LoggingManagerImpl.java:142-150`) which wraps step execution in `begin()`/`end()` calls. The log writer is a `ThreadLocal`-backed chain (`ThreadBoundLogOutputStream:35-36`, `OverridableStreamingLogWriter:30-31`).
5. **A step executes on the node recorded in `Execution.serverNodeUUID`.** Cluster-aware code paths (e.g., `abortExecutionDirect` in `ExecutionService.groovy:1855-1887`) treat `serverNodeUUID != currentNode` as "route to the other node." Steps do not migrate mid-execution.
6. **A step's execution is bounded by `Execution.dateStarted`/`dateCompleted`.** The reaper uses `dateStarted < before` + `dateCompleted IS NULL` as the orphan predicate (`ExecutionService.groovy:798-812`). Anything that sits too long without `dateCompleted` being set is a candidate for cleanup.
7. **A step runs inside a Quartz job that `.join()`s on the execution thread.** `ExecutionJob.groovy:410-535` runs a `while (thread.isAlive() || never) { thread.join(1000); ... }` loop, then unconditionally calls `saveState()` (`:599-705`) which always sets `dateCompleted` (`:622-625`) and fires `triggerJobCompleteNotifications` (`:641-685`).

A step that wants to "suspend" today has only one option: block inside `executeWorkflowStep` via `Thread.sleep` / `Object.wait` / polling. This pins everything in items 3–7 for the wait duration.

---

## 2. What's actually built — code walk

### 2.1 Workflow execution model

**EngineWorkflowExecutor** (`core/src/main/java/com/dtolabs/rundeck/core/execution/workflow/EngineWorkflowExecutor.java:181-338`) is the default executor for all three workflow strategies (Sequential, Parallel, NodeFirst). All strategies route through the same engine — one loop to teach.

The executor:
- Builds a `WorkflowSystem` with a rule engine + `MutableStateObj` at `:246-252`.
- Converts workflow steps to `StepOperation` objects at `:223`.
- Calls `workflowEngine.processOperations(operations, dataContextSharedData)` at `:264` — **blocking until all operations complete or the workflow reaches end state**.
- Iterates result set at `:272-293`, accumulating step failures and control behaviors.

**WorkflowEngineOperationsProcessor.continueProcessing()** (`:139-168`) is the core loop:

```java
while (!Thread.currentThread().isInterrupted()) {
  LoopResult result = processStep();
  if (result == LoopResult.FinishedFinal || LoopResult.FinishedNoMoreChanges) return;
}
```

`processStep()` (`:111-134`) blocks on a `LinkedBlockingQueue.poll()` at `waitForChanges()` (`:363`) waiting for completion events from step executions. Only exits when (a) the workflow reaches end state AND the pending queue is empty, or (b) the thread is interrupted.

**Critical:** the loop has no "early return with partial state" exit. A step returning a suspension signal does not naturally stop the loop — new exit condition is required.

**Parallel strategy** (`ParallelWorkflowStrategy.java:45-54`) makes all steps eligible at `WORKFLOW_STATE_STARTED`, submitted concurrently to an `executorService` via `Futures.addCallback` (`:241-263`). Multiple steps can be in-flight when one suspends — significant complication.

### 2.2 Execution context

**ExecutionContextImpl** (`core/.../execution/ExecutionContextImpl.java:55-99`) mixes pure data with live references:

**Serializable data** (safe to checkpoint):
- `frameworkProject`, `user`, `charsetEncoding` (strings)
- `dataContext`, `privateDataContext`, `sharedDataContext` (nested maps — need verification on lambda-holding edge cases)
- `nodeSet`, `nodes`, `authContext` (probably plain POJOs, but `UserAndRolesAuthContext` may hold live Spring Security session refs — needs confirmation)
- `flowControl`, `outputContext` (data-only so far as found)

**Live references** (cannot serialize):
- `executionListener`, `workflowExecutionListener` (interface implementations wired to log streams)
- `executionLogger` (live sink)
- `framework` (`IFramework` service locator)
- `storageTree`, `jobService`, `nodeService`, `loggingManager`, `pluginControlService` (all live services)
- `componentList` (`List<ContextComponent<?>>`) — **unbounded plugin extension point**, plugins can add arbitrary objects via `.withComponent(...)`. No durability constraint today.

**Hidden state:**
- `ExecutionReference` field of unknown content.
- No explicit parent-context pointer but builder clones from other contexts (`ExecutionContextImpl.java:218-265`) — mutation visibility across clones is unclear.

### 2.3 Logging

**Thread-bound buffering:**
- `ThreadBoundLogOutputStream` (`core/.../utils/ThreadBoundLogOutputStream.java:35-36`) uses `ThreadLocal<Holder<T>> buffer` and `InheritableThreadLocal<LogBufferManager> manager`.
- `OverridableStreamingLogWriter` uses `InheritableThreadLocal<ContextStack<Optional<StreamingLogWriter>>>` (`:30-31`).
- Switching execution to a different thread (e.g., on resume) loses the thread-local chain — must be rebuilt.

**Flush discipline:**
- `LoggingManagerImpl.runWith()` (`:142-150`) calls `begin()` before and `end(result)` after a step. `end()` (`:164-171`) removes the writer override and calls `finish(result)` on the plugin-filtered writer.
- **No explicit flush to disk** in `LoggingManagerImpl`. Flushing depends entirely on the configured streaming writer plugin's buffering behavior. Step completion is NOT a guaranteed sync point unless the underlying writer is synchronous.

**Default writer (`FSStreamingLogWriter`):**
- Instantiated at `LogFileStorageService.groovy:694` with `new FileOutputStream(file)` — **truncating open, no append flag**.
- On open: writes header `^text/x-rundeck-log-v2.0^` via `formatter.outputBegin()` (`FSStreamingLogWriter.groovy:63`).
- On close: writes footer `^END^` via `formatter.outputFinish()` (`:82-94`).
- No `FileChannel.lock()` or OS-level file locking found. Only JVM-scoped `synchronized(this)` on the writer instance.
- The file IS the checkpoint — no separate index/position/state file (`LogFileStorageService.java:96-98`).

**Reader tolerates footerless files:**
- `LogEventLineIterator` treats `^END^` as "complete" (`:142-144`) but the absence of the footer does not fault — live tailing routinely reads in-progress files that have no footer. `ExecutionController.groovy:1835-1836` derives "completed" from job state OR footer presence, not exclusively the footer.

### 2.4 Execution lifecycle and persistence

**Execution domain** (`rundeckapp/grails-app/domain/rundeck/Execution.groovy`):
- Status field is free-form string. Current values (`ExecutionService.groovy:1499-1508`): `running`, `succeeded`, `failed`, `aborted`, `timedout`, `failed-with-retry`, `other`, `scheduled`, `missed`, `queued`. **No `waiting` / `paused` state.**
- `serverNodeUUID` (line 66) records which cluster node is executing.
- `outputfilepath` stores absolute path to the log file on shared FS.
- `runningExecutionsCriteria` (`:169-179`) filters `dateStarted not null AND dateCompleted null AND status NOT IN (scheduled, queued)`. **Does not filter by `serverNodeUUID`** — NULL UUID counts as "running."
- `getExecutionState()` (`:283-307`) hardcodes status-string → enum mappings. Adding a new status requires updating this.

**ExecutionService.executeAsyncBegin** (`:1031-1284`):
- Opens log writer at `:1059-1064`.
- Persists `outputfilepath` at `:1065-1066`.
- Creates `WorkflowExecutionServiceThread` at `:1266`.
- Starts thread at `:1274`.
- Returns `AsyncStarted` holding a live thread reference at `:1277`.

**ExecutionJob.executeCommand** (`rundeckapp/grails-app/jobs/rundeck/quartzjobs/ExecutionJob.groovy:410-535`):
- `while (thread.isAlive() || never) { thread.join(1000); ... check timeout/threshold ... }`
- After loop: `runContext.executionUtilService.finishExecution(execmap)` at `:517`.
- Returns `RunResult(success, execmap, result)` at `:534`.

**ExecutionJob.saveState** (`:599-705`):
- Unconditionally sets `dateCompleted = new Timestamp(System.currentTimeMillis())` at `:622-625`.
- Unconditionally calls `triggerJobCompleteNotifications` at `:641-685`.
- **No branch for "non-terminal outcome."** Any path that returns from the execution thread marks the execution completed.

### 2.5 Cluster mode

**Quartz configuration** (`QuartzConfig.groovy:21`):
```groovy
quartz {
    autoStartup = true
    jdbcStore = false   // RAM-only, NOT clustered
}
```

**Implication:** Each Rundeck node runs its own in-memory Quartz scheduler. Job definitions are not shared across nodes via Quartz. When node A dies, its Quartz jobs are lost — no Quartz failover. Cross-node dispatch cannot use "enqueue a Quartz one-shot and let any node pick it up."

**Cluster messaging** (`ExecutionService.groovy:1859-1870`):
- Uses Grails `EventPublisher.sendAndReceive('cluster.abortExecution', ...)`.
- Synchronous / in-memory by default — real distributed transport requires a plugin.
- 30-second timeout at `:1884`.
- If target node is offline, closure never fires, `fresult.get(30s)` times out.

**Takeover pattern** (`ScheduledExecutionService.reclaimAndScheduleJobs:1076`, `claimScheduledJobs:823`):
- Exists for scheduled jobs, not executions.
- DB-atomic `UPDATE ... WHERE serverNodeUUID = :fromNode`.
- Reusable pattern for resume: mirror the UPDATE shape against `Execution` rows.

**Liveness detection:**
- No heartbeat table, no cluster-member registry.
- Liveness is implicit: send a message, wait for response, time out after 30s.
- This forces an unowned-on-suspend model if we want two-node resume — we cannot reliably check "is the originating node still up."

**Orphan cleanup** (`ExecutionService.cleanupRunningJobs:798-812`):
```groovy
def findRunningExecutions(serverUUID, before=new Date()) {
    Execution.runningExecutionsCriteria.list {
        lt('dateStarted', before)
        if (serverUUID != null) { eq('serverNodeUUID', serverUUID) }
        else { isNull('serverNodeUUID') }
    }
}
```
- Uses `dateStarted` staleness only; no heartbeat column.
- **A waiting execution sitting >30s (or configured threshold) will be reaped as orphaned** unless the criteria excludes the new `waiting` status.

### 2.6 Sub-workflow execution

**SubWorkflowWorkflowStepExecutor** (`:23-25`) → `ExecutionService.executeWorkflowStep` (`:4164-4227`):
- Spawns a fresh `WorkflowExecutionServiceThread` at `:4196-4198`.
- Calls `executionUtilService.runRefJobWithTimer(thread, startTime, false, 0)` at `:4202`.
- `runRefJobWithTimer` performs a blocking `thread.join()`.
- **The parent workflow thread blocks** on the child workflow's thread for the entire child duration.
- A suspend inside a child workflow does not release the parent's thread without an explicit cascade mechanism.

### 2.7 Default streaming log writer (verification round)

**FSStreamingLogWriter** (`rundeckapp/src/main/groovy/com/dtolabs/rundeck/app/internal/logging/FSStreamingLogWriter.groovy`):
- Opens file in **truncating mode** via `new FileOutputStream(file)` at `LogFileStorageService.groovy:694`. No append flag.
- Writes header `^text/x-rundeck-log-v2.0^` on open (`RundeckLogFormat.java:78-79`).
- Writes footer `^END^` on close (`:83-84`).
- Between: one event per line, format `^<date>|<eventType>|<loglevel>|<metadata>|<message>^`.
- **No OS-level locks** (no `FileChannel.lock` / `FileChannel.tryLock`).
- JVM-scoped `synchronized(this)` only — does not protect across processes or nodes.

**Reader path** (`FSStreamingLogReader.groovy`):
- `openStream(offset)` opens file with `new FileInputStream(file)` at `:103`. Not held across calls.
- `ExecutionController.groovy:1783` opens on each tail request, closes at `:1842`.
- Tolerates missing footer; derives completion from job state OR footer presence.
- Reading while another node writes is safe under POSIX append semantics.

**Naive handoff verdict:** UNSAFE without modification. Node B taking the default path would truncate the file, then write a second header in the middle. Even append-mode reopen would leave a spurious `^END^` marker mid-file that readers treat as end-of-log.

**Surgical fix identified:** Add `StreamingLogWriter.suspend()` (close without footer) and a resume-mode factory (open append, skip header). Single writer class to modify. Reader path unchanged.

---

## 3. Gap inventory

### 3.1 Missing (feature does not exist)

- **No suspend primitive in the workflow engine.** Step results are binary success/fail; the processor loop has no early-exit condition; the step execution context has no `suspend(...)` method.
- **No `waiting` execution status.** Lifecycle only supports terminal transitions.
- **No checkpoint persistence.** Execution state is held in memory on the thread; no DB representation of "partway through a workflow, waiting for input."
- **No resume entry point.** `ExecutionService.executeAsyncBegin` is the only execution-start path; no `executeAsyncResume`.
- **No cross-node resume mechanism.** `sendAndReceive` exists for abort but has no symmetric resume handler; also unnecessary if we use DB-polling instead.
- **No plugin SPI for declaring durability** of `ContextComponent` entries. Plugins can stuff arbitrary live-service references into `componentList` today.

### 3.2 Incomplete / breaks on the first wrong move

- **`ExecutionJob.saveState` cannot represent non-terminal outcomes.** Unconditionally sets `dateCompleted` and fires completion notifications. Any attempt to return early from the execution thread will mark the execution completed and fire workflow-end events prematurely.
- **`Execution.runningExecutionsCriteria` will reap waiting rows.** Adding a `waiting` status without updating the criteria is a silent data-loss bug.
- **`LoggingManager.runWith` does not flush to disk.** `end(result)` only releases the override and calls `finish()` on the plugin writer. Relying on "step boundary is a sync point" is unsafe without explicit `flush()`.
- **`FSStreamingLogWriter` header/footer framing breaks naive append.** Resume path must use a new suspend/resume SPI, not just `FileOutputStream(file, true)`.
- **`WorkflowEngineOperationsProcessor` loop blocks until workflow end state.** Has no "stop after step N with partial results" mode. Requires new loop-exit condition.

### 3.3 Wrong (will need correction when added)

- **`abortExecutionDirect` looks up a Quartz job that doesn't exist** for waiting executions. A waiting execution has already returned from its thread and is not in Quartz; the abort path as-written would fail to find it and leave it in `waiting` forever.
- **Child workflow suspension would block the parent on `.join()`.** Without a cascade mechanism, suspend inside a sub-workflow is a deadlock trap.

### 3.4 Untested

- No existing tests for "execution thread returns cleanly with a non-terminal outcome." The code path doesn't exist.
- No existing tests for cluster handoff of mid-execution state. Orphan cleanup is tested for terminal states.
- No integration test for multi-node log file append on shared FS.

---

## 4. Data flow analysis

What crosses boundaries today, and what has to cross on suspend/resume.

### 4.1 Context flow

```
ExecutionService.executeAsyncBegin
  → builds ExecutionContextImpl (data + live refs)
  → passes to WorkflowExecutionServiceThread (thread-local)
  → passes to EngineWorkflowExecutor.executeWorkflowImpl
  → passes to StepOperation (wraps executor + context per step)
  → passes to StepExecutor.executeWorkflowStep (plugin boundary)
```

**For suspend/resume**, the context has to survive a round-trip through the DB. Today it never does. The data-only subset must be identified and serialized; the live-reference subset must be rehydrated from the project + framework on resume.

**Typed boundary at the plugin layer.** The plugin sees `StepExecutionContext` (an interface). Today the underlying `ExecutionContextImpl` is fully populated. On resume, the interface contract must still be satisfied — the rehydrated context must look indistinguishable to the plugin. Plugin code that stored references to context collaborators from a previous invocation would see stale identity.

### 4.2 Log flow

```
Step plugin calls logger
  → ExecutionListener (thread-local binding)
  → LoggingManager (runWith wrapper)
  → OverridableStreamingLogWriter (InheritableThreadLocal chain)
  → ThreadBoundLogOutputStream (ThreadLocal buffer)
  → FSStreamingLogWriter (header → events → footer)
  → FileOutputStream (no locks, no append)
  → outputfilepath on disk
```

**For suspend/resume**, every thread-bound link in the chain is invalidated when the execution thread exits. On resume, the chain must be rebuilt on a new thread, and the file must be reopened without disturbing the framing. The single surgical change is at the bottom of the chain: `FSStreamingLogWriter` gains a suspend-close (no footer) and a resume-open (append, no header).

**Single-writer invariant** is maintained because the DB-atomic claim guarantees only one node has a live writer for a given execution at a time. Cross-node flow:

```
Node A: suspend
  → flush writer
  → suspend-close FSStreamingLogWriter (no footer)
  → close FileOutputStream
  → DB transaction: status='waiting', serverNodeUUID=NULL
  (file on shared FS is now footerless, in-progress-looking, readable by tailing)

Node B: resume (after claim via atomic UPDATE)
  → open FileOutputStream(file, true)  // append mode
  → construct FSStreamingLogWriter in resume mode (skip header)
  → build new ExecutionListener chain on the new thread's thread-locals
  → resume engine
```

### 4.3 Thread and state flow

```
Quartz ExecutionJob.executeCommand (per-execution Quartz thread)
  → thread.start()
  → thread.join(1000) in loop (per-execution thread blocks Quartz thread)
    → WorkflowExecutionServiceThread.run()
    → EngineWorkflowExecutor.executeWorkflowImpl
    → WorkflowEngineOperationsProcessor loop
    → StepExecutor.executeWorkflowStep
    → plugin code
  (step completes)
  → processor loop continues OR exits at end state
  → thread.run() returns
  → thread.isAlive() false → join loop exits
  → finishExecution(execmap)
  → saveState (dateCompleted = now)
  → triggerJobCompleteNotifications
```

**For suspend/resume**, the flow must support an alternate termination path:

```
... (plugin returns SuspendedStepResult)
  → StepExecutor returns SuspendedStepResult
  → processor loop detects suspend, drains queue, exits
  → EngineWorkflowExecutor aggregates result as isSuspended=true
  → thread.run() returns with suspended result on the thread object
  → Quartz join loop exits
  → ExecutionJob checks thread.isSuspended() BEFORE finishExecution
  → routes to onWorkflowSuspended (new path)
  → saveState short-circuits (no dateCompleted, no notifications)
  → Quartz thread released
```

On resume, a new `WorkflowExecutionServiceThread` is spawned by the resume worker (not by Quartz this time — Quartz is RAM-only per node, not a cluster dispatch mechanism). The rehydrated context is fed in; the thread re-enters `EngineWorkflowExecutor` with a resume payload injected into the previously-suspended step's re-execution.

---

## 5. User-facing documentation gaps

This feature touches three audiences:

### 5.1 Plugin authors

**Current docs:** The `WorkflowStepPlugin` and `WorkflowNodeStepPlugin` SPI documentation describes a synchronous `executeWorkflowStep` contract. No guidance on waiting for external events; authors invent polling loops.

**What's missing for v1:**
- How to call `context.suspend(...)`, what it does, when it returns, what happens to the step if the wait times out.
- The durability contract for `ContextComponent` entries (new `DurableContextComponent` marker).
- What state plugins can assume about rehydrated context after resume (same data, fresh identities for collaborators).
- How to emit log events during the pre-suspend phase (normal), and what happens if the plugin logs after calling `suspend()` (it shouldn't — document clearly).
- How approval / external trigger mechanisms deliver resume payloads (DB-backed; webhook writes `resume_ready=true` + payload).

### 5.2 Operators (cluster-mode deployment)

**Current docs:** Rundeck cluster mode documentation covers shared DB, shared filesystem, `serverNodeUUID`, scheduled job takeover. No documentation of in-flight execution handoff — because none exists today.

**What's missing for v1:**
- Shared filesystem append semantics requirement (POSIX append, NFSv4 preferred, `actimeo=0`/`noac` for NFSv3).
- The new `waiting` execution status and how it's visualized.
- Resume worker configuration (poll interval, enable/disable).
- Operational runbook for "waiting execution stuck because approval never came" — workflow-level timeout vs. manual abort.
- Cluster-mode behavior when a node dies with waiting executions in-flight: they're recoverable because they're `serverNodeUUID=NULL`.

### 5.3 Workflow authors (job definitions)

**Current docs:** Job definition YAML/XML covers steps, error handling, notifications. Workflow authors don't call `suspend` directly but will use step plugins that do (e.g., the rx confirm step).

**What's missing for v1:**
- How a "waiting" execution displays in the UI and API.
- Whether notifications fire on suspend (no — only on true completion).
- How timeouts interact with suspension (the step-level timeout governs the wait; workflow-level timeout also ticks).
- That waiting does not count toward "concurrent execution" limits in the same way as running (or does it — open decision).

---

## 6. Open questions with use-case walkthroughs

Spec-cycle practice: walk concrete scenarios to find what the spec must address. Walkthroughs are distinct from audits; they find silence and contradictions.

### 6.1 Walkthrough A — Single-node approval (10 minutes)

**Scenario:** Single Rundeck node. Job runs 3 steps. Step 2 is `rx confirm`. Approver clicks "approve" 10 minutes after step 2 begins.

| # | Event | Handler | Gaps found |
|---|---|---|---|
| 1 | Job starts | Quartz → `ExecutionJob` → `ExecutionService.executeAsyncBegin` | — |
| 2 | Thread spawns, `EngineWorkflowExecutor` enters | `WorkflowExecutionServiceThread` | — |
| 3 | Step 1 runs, succeeds | Step plugin | — |
| 4 | Step 2 (`rx confirm`) begins | Step plugin calls `context.suspend(SuspendRequest(token, timeout=3600s, reason="awaiting approval"))` | **GAP A1:** `context.suspend` API shape undefined. Return-via-exception vs. return-a-result. |
| 5 | Engine sees suspended step result | `WorkflowEngineOperationsProcessor` exits loop early, `EngineWorkflowExecutor` builds suspended `WorkflowExecutionResult` | **GAP A2:** Precise loop-exit semantics — does the processor drain pending completions before exiting, or cancel them? Sequential mode has no pending, so it's moot for v1, but the code must still handle the empty-drain case correctly. |
| 6 | Execution thread returns | `WorkflowExecutionServiceThread.run()` completes | **GAP A3:** Where does the thread store the suspended result so `ExecutionJob` can see it? Likely a `result` field, same as success/fail. Confirm on read. |
| 7 | Quartz `executeCommand` sees `thread.isAlive()=false`, exits join loop | `ExecutionJob` | — |
| 8 | Quartz calls what? | **GAP A4:** Today: `finishExecution` + `saveState`. New: must branch on `thread.isSuspended()` before these calls. Where exactly — before `finishExecution` or inside `saveState`? Decision: before both, route to new `executionService.onWorkflowSuspended(execmap)`. |
| 9 | `onWorkflowSuspended`: flush log writer, suspend-close writer, persist checkpoint, set `status='waiting'`, clear `serverNodeUUID`, set `wait_started_at=now()` | New method | **GAP A5:** Ordering within the transaction. Log flush is I/O outside the DB tx. Order: flush → close → DB commit. Crash between flush and commit leaves execution `running` with footerless log — reaped normally. |
| 10 | Quartz thread released | `ExecutionJob` returns | **GAP A6:** Quartz-side metrics / timers. `ExecutionJob` records duration, thresholds, etc. Do these get reset on resume or accumulated? Decision: accumulated. `waitStartedAt` tracks the wait portion separately. |
| 11 | Approver clicks "approve" in UI | UI sends webhook / API call → `WebhookController` → plugin handler | **GAP A7:** Webhook plugin writes `resume_ready=true`, `resume_payload={approved: true, approver: "alice", ...}` directly to `Execution` row. Does the plugin need a new grails service method, or call the DB directly? Decision: new service method `ExecutionResumeService.markResumeReady(execId, payload)`. |
| 12 | Local resume worker polls DB (1-2s later) | New `ExecutionResumeWorker` bean | **GAP A8:** Worker lifecycle — startup, shutdown, error handling, missed ticks. Spring `@Scheduled` vs. local Quartz job. Decision: Spring-scheduled (simpler; local Quartz is RAM-only so same effective scope). |
| 13 | Worker queries `waiting AND resume_ready AND serverNodeUUID IS NULL` | Worker | — |
| 14 | Atomic claim via UPDATE | Worker | **GAP A9:** Claim-update returning 0 rows (someone else got it) vs. 1 row (we won). Worker must detect and skip. Mirror `claimScheduledJobs` pattern. |
| 15 | Rehydrate `ExecutionContextImpl` from checkpoint blob | Worker → new `ExecutionContextImpl.Builder.fromCheckpoint(...)` | **GAP A10:** Rehydration must reconstruct live references (listener, logger, framework, storageTree, nodeService) from project + execution id. Which grails service owns this? Decision: `ExecutionService.buildExecutionContextFromCheckpoint(execId, checkpointData)`. |
| 16 | Re-open log writer in append+resume mode | Worker → `LogFileStorageService.getLogFileWriterForResume(exec)` | — |
| 17 | Spawn fresh `WorkflowExecutionServiceThread` with rehydrated context + resume payload | Worker | **GAP A11:** Entry point into the engine on resume. `EngineWorkflowExecutor` must accept "resume this workflow starting at step N with this payload fed into step N." Decision: new `executeWorkflowResume` entry method distinct from `executeWorkflowImpl`. |
| 18 | Engine re-enters, step 2's `execute()` is re-invoked | Step plugin | **GAP A12:** How does the step see the resume payload? Via a re-entrant call to `context.suspend()` that returns a `SuspendResult` object? Or via a new method `context.getResumePayload()`? Decision: the step plugin uses a distinct API — `context.suspend(...)` returns a `SuspendedStepResult` sentinel the first time; on resume, the engine calls the step again with the resume payload accessible via `context.getResumePayload()`. Needs validation in Phase 2 spec. |
| 19 | Step 2 completes (approved) | Step plugin returns success | — |
| 20 | Engine continues to step 3 | `WorkflowEngineOperationsProcessor` | — |
| 21 | Step 3 runs, succeeds | — | — |
| 22 | Workflow completes normally | `EngineWorkflowExecutor` | — |
| 23 | Normal close path writes `^END^` footer | `FSStreamingLogWriter.close()` | **GAP A13:** Was the file opened in resume mode (header skipped) for the final segment? Yes — the write is append, footer goes at the end, reader sees exactly one header + all events + exactly one footer. |
| 24 | `dateCompleted` set, notifications fire | `ExecutionJob.saveState` | — |

**Walkthrough A proves:** Single-node suspend/resume is feasible. 13 gaps found, all resolvable in the Phase 2 spec. The critical design decisions are (A1) suspend API shape, (A11) resume entry point into the engine, (A12) how the step plugin sees the resume payload.

### 6.2 Walkthrough B — Two-node cluster, approval lands on different node

**Scenario:** Two Rundeck nodes (A and B) sharing DB + filesystem. Job starts on node A. Step 2 suspends. Load balancer routes the approval webhook to node B.

| # | Event | Handler | Gaps found (new, beyond A) |
|---|---|---|---|
| 1-10 | Same as Walkthrough A through "Quartz thread released on node A" | — | — |
| 11 | Webhook lands on node B | Node B's `WebhookController` | — |
| 12 | Webhook plugin on B writes `resume_ready`, `resume_payload` to DB | Node B | — |
| 13 | Node B's polling worker wakes next tick (within 1-2s) | Node B's `ExecutionResumeWorker` | — |
| 14 | Node B queries `waiting AND resume_ready AND serverNodeUUID IS NULL` | Node B | — |
| 15 | Node B atomic-claims | Node B | **GAP B1:** Race — both A and B might poll simultaneously. Claim UPDATE is atomic; whoever commits first wins; loser sees 0 rows and skips. Safe. |
| 16 | Node B rehydrates context from checkpoint | Node B | **GAP B2:** Rehydration on node B must use node B's `framework`, `loggingManager`, etc. — not node A's (node A's references are stale and possibly garbage-collected). The checkpoint blob stores execution-id only; rehydration looks up locally. |
| 17 | Node B opens log file in append+resume mode | Node B | **GAP B3:** Shared FS read-your-writes consistency. Node A's close on the file must be visible on node B before node B's open. POSIX guarantees this if close completed before the DB commit visible to B. Order: flush log → close → DB commit → B sees commit → B opens log. Single-writer invariant preserved. |
| 18 | Node B's polling worker must NOT double-process | Node B | **GAP B4:** Once claimed (serverNodeUUID=B), the execution is no longer eligible for polling (claim WHERE clause excludes it). Safe. |
| 19 | Node B spawns `WorkflowExecutionServiceThread` with rehydrated context | Node B | — |
| 20-24 | Continue as Walkthrough A | Node B | **GAP B5:** On final completion, `dateCompleted` is set by node B's `ExecutionJob`. But this is NOT running as a Quartz job on node B — it's running inside the resume worker's thread pool. The path that sets `dateCompleted` and fires notifications must work outside of `ExecutionJob`. Decision: resume worker invokes the same `finishExecution` + `saveState` code path that `ExecutionJob` invokes, factored out of the Quartz-specific wrapper if necessary. |

**Walkthrough B proves:** Two-node handoff is feasible via the unowned-on-suspend + atomic-claim pattern. No cross-node messaging required. 5 additional gaps, all resolvable. The critical decision is (B5) factoring `finishExecution` / `saveState` out of the Quartz-specific path so the resume worker can invoke it.

### 6.3 Walkthrough C — Node A dies mid-execution (before suspend)

**Scenario:** Node A is running a workflow with a non-suspending step. Node A crashes before the step completes. Node B has no direct knowledge.

| # | Event | Handler | Gaps found |
|---|---|---|---|
| 1 | Node A crashes | Hardware / OS / JVM | — |
| 2 | Node A's Quartz thread pool dies | — | — |
| 3 | Execution row is: `status=running`, `serverNodeUUID=A`, `dateCompleted=null`, `dateStarted=10 min ago` | DB | — |
| 4 | Node B's reaper runs | `cleanupRunningJobs` | **GAP C1:** Reaper uses `dateStarted < before` + `serverNodeUUID=A`. If called with `serverUUID=A`, finds the row, marks it `incomplete`. Existing behavior. |
| 5 | Node B does NOT resume | — | Existing behavior: Rundeck has no in-flight execution takeover today. Row ends in `incomplete`, not restarted. Acceptable for v1 — this walkthrough describes the pre-suspend case, not a suspend/resume case. |

**Walkthrough C proves:** Nothing new. Confirms the existing reaper path handles node-A-crashed-during-non-suspended-execution. Does not exercise suspend/resume. Included for completeness so reviewers see we haven't accidentally broken the existing behavior.

### 6.4 Walkthrough D — Node A dies while executions are in `waiting` state

**Scenario:** Two executions are `status=waiting`, `serverNodeUUID=NULL` (unowned). Node A (the node that originally started them) dies. Approvals for both arrive shortly after on node B.

| # | Event | Handler | Gaps found |
|---|---|---|---|
| 1 | Node A dies | — | — |
| 2 | Execution rows are `waiting`, unowned | DB | — |
| 3 | Node B's reaper runs | `cleanupRunningJobs` | **GAP D1:** Does the reaper touch `waiting` rows? Must NOT. Mitigation: `runningExecutionsCriteria` must exclude `status='waiting'`. Already in plan. |
| 4 | Approvals arrive on node B | — | — |
| 5 | Node B's polling worker claims and resumes | — | — |

**Walkthrough D proves:** Unowned-on-suspend + reaper-exclusion jointly handle the "originating node died" case without any explicit takeover code. The shared DB + FS does all the work.

### 6.5 Walkthrough E — Job Reference step suspends inside a child workflow

**Scenario:** Parent workflow calls a child workflow via Job Reference step. Child's step 2 suspends.

| # | Event | Handler | Gaps found |
|---|---|---|---|
| 1 | Parent step N runs Job Reference | `SubWorkflowWorkflowStepExecutor` | — |
| 2 | `executionUtilService.runRefJobWithTimer(thread, ...)` | Parent thread | — |
| 3 | Child's thread starts, enters engine | — | — |
| 4 | Child's step 2 suspends | — | **GAP E1:** Child engine exits with suspended result. Child thread returns. Parent's `runRefJobWithTimer` was `.join`-ing on the child thread. Parent sees the child thread exit. |
| 5 | Parent's `runRefJobWithTimer` returns what? | — | **GAP E2:** The return value of `runRefJobWithTimer` is a workflow result. If the child suspended, it should return a suspended result to the parent's step executor. Today the return value is a terminal workflow result; there is no "suspended" variant propagating up. |
| 6 | Parent's step executor sees the Job Reference return | — | **GAP E3:** To cascade, the Job Reference step executor must detect child-suspended and itself return `SuspendedStepResult` to the parent's engine. The parent then suspends too. |
| 7 | Parent suspends, parent thread exits | — | Correct behavior IF cascade is wired. |
| 8 | On resume of the child (via polling worker), the parent is still waiting | — | **GAP E4:** Parent's suspension was tied to the child's suspension. When the child resumes on (possibly) a different node and completes, how does the parent see "child is done"? The parent is also in `waiting` state with its own checkpoint. The parent's resume trigger is "child execution is complete." Need a mechanism: either child completion marks the parent's `resume_ready`, or the parent's polling worker checks child status on every tick. |

**Walkthrough E proves:** Sub-workflow cascade is solvable but non-trivial. 4 gaps, all requiring new machinery. **Decision:** v1 forbids suspend inside sub-workflows (fail fast at `context.suspend` time if the workflow is a child). v2 (later cycle) adds cascade. Non-goal for this cycle.

### 6.6 Walkthrough F — Approval never arrives (timeout)

**Scenario:** Step suspends with `timeoutMs=3600000` (1 hour). Nothing approves. Timeout expires.

| # | Event | Handler | Gaps found |
|---|---|---|---|
| 1-10 | Suspend as in Walkthrough A | — | — |
| 11 | 1 hour passes | — | — |
| 12 | What watches the timeout? | — | **GAP F1:** Who enforces `waitTimeout`? Options: (a) polling worker checks `wait_started_at + timeoutMs < now()` and marks as failed on expiry; (b) a separate scheduled timeout sweep; (c) workflow-level timeout fires regardless. Decision: (a) — polling worker is already running, adding a timeout-expiry check is cheap. |
| 13 | Worker sees expired execution | — | **GAP F2:** Expired execution gets claimed the same way, but the re-entered step is told "your wait timed out." The step plugin receives a synthetic resume payload indicating timeout and can fail / take an alternate action. Same as approval-denied today — throw `NodeStepException` with reason `ConfirmTimeout`. |

**Walkthrough F proves:** Timeout enforcement reuses the resume path with a synthetic timeout payload. No separate timeout mechanism needed.

---

## 7. Vocabulary stress-test

The methodology requires: for each new item introduced, complete the sentence "The author writes this when they intend to X." If X names a mechanism rather than a goal, the item is misclassified.

The audience for this feature's vocabulary is **step plugin authors**, not workflow authors (workflow authors don't write `suspend` — they configure a plugin that calls it).

### 7.1 `suspend` / `SuspendRequest`

> The plugin author writes `context.suspend(...)` when they intend to **wait for an external event (approval, external system callback, human input) without holding the execution thread**.

**Passes the test.** "Wait for an external event" is a goal, not a mechanism. This maps to rx's `wait-for` intent-vocabulary bucket — semantically identical, different runtime.

**Refinement:** the mechanism (thread release, DB persistence, resume-on-event) is the *implementation* of "wait for external event" — authors should not think in those terms. The SPI documentation should lead with "use suspend when you need to wait for something that isn't going to happen in the next few seconds" and bury the DB/thread mechanics in a secondary section.

### 7.2 `SuspendedStepResult`

> (Not author-facing.) Internal engine representation of "the step wants to suspend."

**Not subject to the test** — it is an engine-internal type not called by plugin authors. The plugin author calls `context.suspend(...)`; the engine converts that into a `SuspendedStepResult` behind the scenes. Keep it internal.

### 7.3 `DurableContextComponent`

> The plugin author implements `DurableContextComponent` when they intend to **declare that a context component they contribute can survive a checkpoint/restore cycle**.

**Passes the test** as a plugin-SPI contract. The goal ("survive checkpoint/restore") is a property of the component, not a mechanism. However: the term "durable" is overloaded in distributed systems (durable messages, durable subscriptions, durable storage). Consider `SuspendSafeContextComponent` or `CheckpointableContextComponent` for clarity — decide in Phase 2 spec.

### 7.4 `waiting` (execution status)

> The operator sees `waiting` when they intend to know **whether the execution has terminated, is actively running, or is parked pending an external event**.

**Passes the test.** `waiting` is a user-visible lifecycle state, not an implementation detail. Distinct from `running`, `scheduled`, `queued` on the axis of "what is it doing": `running` = actively consuming resources, `waiting` = persisted and awaiting an event, `scheduled`/`queued` = not started yet.

### 7.5 `resume` (operator action)

> The operator/webhook runs `resume` when they intend to **deliver the event that a waiting execution is waiting for**.

**Passes the test.** Note: "resume" is not a user-driven action in the primary flow — the webhook/API provides the event, and the engine resumes automatically via the polling worker. An operator-initiated "force resume" is a secondary use case for debugging.

### 7.6 No vocabulary rejects

All new primitives map to plugin-author or operator goals without naming mechanisms. No category errors found. No rejections; no rename required (except possibly `DurableContextComponent` → something clearer, TBD in Phase 2).

---

## 8. Answers and decisions

Decisions locked by this audit (reviewed in conversation across three research rounds, confirmed here):

1. **Unowned-on-suspend ownership model.** `serverNodeUUID = NULL` while waiting. Claim via DB atomic UPDATE. No node-liveness detection required.
2. **DB-polling resume worker per node.** Quartz clustering is RAM-only; cannot be used for cross-node dispatch. Spring-scheduled bean, 1–2s tick.
3. **Single file per execution across suspend/resume.** Reopened in append mode via new SPI; header written once at original start, footer written once at final completion. Reader tolerates footerless intermediate state (live tail already does).
4. **Sequential strategy only for v1.** Parallel workflow suspend deferred to a future cycle — multi-step in-flight quiesce is a separate project.
5. **Sub-workflow suspend forbidden for v1.** Cascade through parent's blocking `.join()` is non-trivial; deferred. Fail fast at `context.suspend()` time when running inside a child workflow.
6. **Log writer suspend-close / resume-open SPI addition.** Single writer class to modify (`FSStreamingLogWriter`). Third-party writer plugins must opt in; otherwise suspend is rejected with a clear error.
7. **Reaper excludes `waiting` rows.** `Execution.runningExecutionsCriteria` gets a `ne('status', 'waiting')` predicate. A separate policy handles abandoned-waiting expiry via the existing timeout mechanism.
8. **Shared-FS cluster mode is a first-class target.** Single-node is a degenerate case of the same code path — no separate single-node implementation.
9. **Log flush is explicit.** Don't rely on `LoggingManager.end()` as a sync point; call `flush()` on the writer explicitly before closing on suspend.
10. **Transaction ordering:** flush log → suspend-close writer → DB commit. Crash between flush and commit leaves execution in pre-suspend state with footerless log — reaped normally; no data loss.
11. **Resume payload mechanism:** the suspended step is re-invoked on resume with the payload accessible via `context.getResumePayload()` (or equivalent). The step treats "no payload" as first call, "payload present" as resume. Exact API shape to be locked in Phase 2 spec.
12. **v1 uses `EXECUTION_WAITING = "waiting"` as the status string.** Added to `ExecutionService` constants. `Execution.getExecutionState()` updated. UI treats as unknown (acceptable for v1; UI polish in a follow-up).
13. **Factor `finishExecution` / `saveState` out of `ExecutionJob` if needed** so the resume worker can invoke the same terminal-write path without being a Quartz job. Decision locked in Walkthrough B, gap B5.

Decisions deferred to Phase 2 spec:

- **Suspend API shape:** exception-based (`throw new SuspendRequest(...)`) vs. return-based (`return context.suspend(...)` — new return type). Return-based is cleaner but may conflict with the existing step-result interface. Walkthrough A gap A1.
- **Resume entry point into the engine:** new `executeWorkflowResume` method vs. parameterized `executeWorkflowImpl(..., resumePayload)`. Walkthrough A gap A11.
- **Resume payload accessor:** `context.getResumePayload()` vs. injection into the step's `execute(context, ...)` signature. Walkthrough A gap A12.
- **Checkpoint serialization format:** JSON (Jackson), Java serialization, or Kryo. JSON preferred for schema evolution and DB readability; needs sizing benchmarks.
- **`DurableContextComponent` naming.** Consider `SuspendSafeContextComponent` or `CheckpointableContextComponent`. Stress-test §7.3.
- **Resume worker poll interval:** default 2s, configurable via system property. Final default TBD.
- **Whether `SuspensionPolicy` validates writer capability** or the log-writer layer does it. Centralized in the policy is cleaner.
- **Should `findWaitingExecutions` exist as a distinct query** for operational visibility, separate from `findRunningExecutions`? Leaning yes.

---

## 9. What would break if the current engine were changed naively

Risk register of "obvious-looking changes that actually break something":

- **Returning early from `EngineWorkflowExecutor.executeWorkflowImpl` with partial results:** marks the workflow as failed in `BaseWorkflowExecutor`'s event-firing path. Workflow-end events fire prematurely. Must teach `BaseWorkflowExecutor` about the third state.
- **Calling `Thread.interrupt` on a suspended execution:** existing abort relies on interrupt; a suspended execution is not on a thread and so cannot be interrupted. The abort path for `status='waiting'` must bypass Quartz lookup and update the DB directly.
- **Truncating log file on resume via `new FileOutputStream(file)`:** erases node A's logs. Resume path must use append mode + skip header.
- **Relying on Quartz to dispatch resume work to a healthy node:** Quartz is RAM-only. Use DB polling.
- **Checking node liveness before routing resume:** no heartbeat table exists. Use unowned-on-suspend instead.
- **Appending after a `^END^` footer:** reader terminates at the footer. Suspend-close must not write the footer.
- **Allowing `waiting` in `findRunningExecutions` without exclusion:** reaper marks waiting rows as orphaned and sets them incomplete. Silent data loss.
- **Stuffing live services into `componentList` from a plugin:** checkpoint serialization fails or silently drops them; on resume the plugin sees `null` where a collaborator used to be, likely NPE. Fail fast at suspend time via `DurableContextComponent` check.

---

## 10. Artifact linkage

- **This audit:** `docs/audits/workflow-suspend-resume.md` — Phase 1 output. Historical reference after Phase 2 spec lands.
- **Next:** `docs/specs/workflow-suspend-resume.md` — Phase 2 output. Declares what the feature *is*. Enduring.
- **Then:** `docs/cycles/workflow-suspend-resume.md` — Phase 2/3 wave sequence, decisions, review findings. Already drafted; will be updated as waves land.

---

**⏸ PAUSE. Present to user. Do not begin Phase 2 spec drafting until audit review is complete.**
