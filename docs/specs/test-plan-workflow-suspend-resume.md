# spec/test-plan-workflow-suspend-resume

**Status:** Draft — consolidates test strategy and success criteria from across the cycle's artifacts into a single reviewable surface.
**Scope:** Test methodology and success criteria for the workflow suspend/resume primitive and the first-class `ConfirmWorkflowStep` plugin it ships with.
**Depends on:**
- `docs/specs/workflow-suspend-resume.md` — §4 invariants, §10 error handling, §11 test strategy (the authoritative source for what must be tested; this document reorganizes and extends).
- `docs/specs/confirm-workflow-step.md` — §13 confirm plugin test strategy.
- `docs/specs/operator-pause.md` — §12 operator-pause test strategy.
- `docs/walkthroughs/workflow-suspend-resume.md` — scenarios that must be exercised end-to-end.
- `docs/cycles/workflow-suspend-resume.md` — cycle-wide exit criteria + per-wave exit criteria.

This is not a new artifact type in the `spec-cycle.md` practice. It exists because test strategy + exit criteria are split across spec §11, confirm-spec §13, and cycle-manifest exit criteria, which is hard to review as a whole. This document consolidates the view. The source specs remain authoritative for any disagreement.

---

## 1. Purpose

Answer two questions precisely and reviewably:

1. **How are we testing this?** (methodology)
2. **How do we know we're done?** (success criteria)

Anyone reviewing an implementation PR in this cycle should be able to open this document, find the tests the PR is supposed to ship, and verify them. Anyone proposing a design change should be able to verify that the change does not weaken any of the stated success criteria.

---

## 2. Methodology

### 2.1 Test layers

Five layers, from fastest/cheapest to slowest/most expensive:

| Layer | Location | Scope | Dependencies | Latency |
|---|---|---|---|---|
| **L1 — Core unit** | `core/src/test/java/.../suspend/` | Pure logic on new types. Jackson round-trip, state-machine logic, `SuspensionPolicy` rejection paths, `FSStreamingLogWriter.suspend()` file-content correctness. | None (no Grails, no Spring, no DB). | ms per test. |
| **L2 — Grails unit** | `rundeckapp/src/test/...` | Service + domain + controller behavior in isolation. Service methods: `ExecutionService.onWorkflowSuspended`, `ExecutionResumeService.*`, `ExecutionTerminalWriteService.writeTerminal`, `ApiConfirmController.*`. | Mocked DB, mocked Spring collaborators. | tens of ms per test. |
| **L3 — Integration (single-JVM)** | `rundeckapp/src/integration-test/...` | Full workflow execution end-to-end in one JVM. Drives a mock suspendable step through the real engine + real persistence + real log writer. | H2 or embedded Postgres. Real file system (tmpdir). | seconds per test. |
| **L4 — Cluster-simulating integration** | Same harness as L3, with `cluster_simulation=true` flag | Every resume in the test forces fresh context rehydration from the checkpoint blob — does NOT reuse any in-memory objects from the pre-suspend execution. Simulates cross-node behavior without two real processes. | Same as L3. | seconds per test. |
| **L5 — Contract tests (for third-party)** | `core/src/test/java/.../suspend/contract/` | Abstract test classes published as a test JAR for third-party plugin authors implementing `CheckpointableStreamingLogWriter` or `CheckpointableContextComponent`. Not run against Rundeck's own implementations in CI (those use L1). | None. | — (not run in Rundeck CI). |

**Not a separate layer, but a distinct mode of running L3/L4:** fault injection. See §2.3.

### 2.2 Required test modes

Every integration test (L3 or L4) MUST run in cluster-simulating mode. It is not a separate test suite; it is a flag on the integration-test harness.

**Rationale (walkthrough gap G25):** A latent portability trap exists where developers write a test on single-node, it passes because in-memory state is preserved in the same JVM, the code ships, and cluster deployments break because the resume node can't reuse that in-memory state. Forcing fresh rehydration on every resume — even in single-JVM tests — closes this gap at test-write time.

**Mechanics of cluster-simulating mode:**
- On resume, the test harness disposes of the original `ExecutionContextImpl` and all its collaborator references before calling `ExecutionResumeService.resumeExecution`.
- `buildExecutionContextFromCheckpoint` is invoked with a freshly constructed framework reference, not the original one.
- Plugin step instances are reinstantiated via their plugin factories; no cached instances.
- Thread-local and `InheritableThreadLocal` state from the pre-suspend thread is NOT inherited by the resume thread.

### 2.3 Fault injection harness

`ExecutionService.onWorkflowSuspended` and `ExecutionResumeService.resumeExecution` both expose pluggable hook points for testing. The harness can inject a failure (throw, crash, OOM) at each of the 7 suspend sub-steps and each of the 8 resume sub-steps. Tests assert the recovery behavior at each injection point.

Suspend-side injection points (mapped to spec §4 I5):

| Point | Before | After | Expected recovery |
|---|---|---|---|
| P1 | log flush | log flush | No-op recovery; flush is idempotent on retry. |
| P2 | writer.suspend (flush+fsync+close) | writer.suspend | Execution stays `running`; log file may or may not have partial fsync. Reaper catches `running` row, marks `incomplete`. |
| P3 | DB transaction begin | DB transaction begin | Execution stays `running`; no DB state change. |
| P4 | metadata + checkpoint write | metadata + checkpoint write | Transaction rollback (if write failed mid-transaction). Execution stays `running`. |
| P5 | status+serverNodeUUID update | status+serverNodeUUID update | Transaction rollback. Execution stays `running`. |
| P6 | DB commit | DB commit | Execution is now `waiting`; crash between commit and notification-fire means notification is never fired. Acceptable — operator sees `waiting` in the UI but no notification. Document as known edge case. |
| P7 | notification fire | notification fire | `waiting` persisted, no notification. Same as P6. |

Resume-side injection points (mapped to spec §4 I6):

| Point | Before | After | Expected recovery |
|---|---|---|---|
| R1 | atomic claim | atomic claim | No row changes. Next tick re-polls. |
| R2 | checkpoint load | checkpoint load | Row is claimed but not progressing. Reaper catches via `last_resumed_at` staleness, marks incomplete. |
| R3 | context rehydration | context rehydration | Release claim (`serverNodeUUID=NULL`), increment `resume_attempt_count`, next tick retries. Exhausts after max attempts → `failed`. |
| R4 | writer openForResume | writer openForResume | Same as R3. Release + retry. |
| R5 | thread spawn | thread spawn | Same as R3. |
| R6 | step re-invocation | step re-invocation | Thread is alive but step threw unexpectedly. Normal step-failure path applies; execution transitions through terminal-write. |
| R7 | remaining workflow steps | remaining workflow steps | Normal workflow failure or success. |
| R8 | terminal write | terminal write | Execution row is `running` without `dateCompleted`; reaper catches via `last_resumed_at` staleness. |

### 2.4 Concurrent-race harness

Tests can spawn N parallel threads representing cluster workers and assert on the race outcome. DB serializability is real (not mocked), so outcomes are deterministic.

Races covered:
- **Claim race:** N resume workers attempt to claim the same eligible row. Exactly one `UPDATE` returns 1 row; others return 0.
- **Approve/deny race:** Two `POST /confirm` requests with conflicting decisions arrive simultaneously. Exactly one succeeds with 200; the other gets 409. The winning decision matches the final execution outcome.
- **Abort-vs-suspend race:** An abort arrives while a suspend transaction is committing. Both outcomes (`waiting → aborted` OR `running → aborted`) are acceptable as long as the final state is consistent.
- **Multiple-resume race:** A step suspends twice in rapid succession (suspend → resume → suspend again), and two resume events arrive before the first resume's commit is visible. Second resume must see `resume_ready=false` (cleared during claim).

### 2.5 Test fixture catalog

Shared across waves, maintained as a single source of truth in the test tree:

| Fixture | Location | Purpose |
|---|---|---|
| `SuspendableMockStep` | `core/src/test/java/.../suspend/fixtures/` | Configurable workflow step plugin. Returns `SuspendedStepResult` on first invocation, reads `context.getResumePayload()` on resume, returns success or failure per test config. Used by engine / service / integration tests. |
| `MockResumePayload` | Same | Opaque `ResumePayload` subtype with `type="mock-test"` discriminator. Keeps engine-level tests decoupled from the confirm plugin. |
| `MockCheckpointableComponent` | Same | Minimal `CheckpointableContextComponent` for I3 checkpoint-completeness tests. |
| `AliceBobCarolScenario` | `rundeckapp/src/integration-test/.../fixtures/` | Canonical scenario from the walkthroughs. Includes: `ops-deploy` project, `prod-deploy-v4` job definition (4-step workflow with built-in `confirm` at step 2), ACL policy with `alice=run`, `bob=run+confirm+sre-role`, `carol=read-only`. Reused across Wave 5 integration tests. |
| `ClusterSimulator` | `rundeckapp/src/integration-test/.../fixtures/` | Test harness wrapping the integration-test context to force fresh rehydration on resume. |
| `FaultInjector` | Same | Test harness for the injection points in §2.3. |
| `RaceHarness` | Same | Spawns N parallel workers against a shared DB state for race tests. |

### 2.6 What is explicitly NOT tested in CI

Honest inventory of scope cuts. Operators and reviewers should know these exist.

1. **Real two-node Rundeck deployment.** Two full Grails processes behind a real load balancer with real shared NFS is prohibitively expensive for CI latency budgets. Cluster-simulating mode is the substitute for automated tests. A real two-node setup is verified manually as a release-checklist item before any release that includes this cycle. See §7 for the checklist.
2. **Production-like approval latency.** Tests use seconds-long timeouts. A real hours-long or days-long wait is not simulated; the mechanics are the same, so a passing short-timeout test implies a passing long-timeout flow.
3. **NFS server failure scenarios.** Partial writes, stale attributes, server reboot, mount re-export. Relies on OS/FS vendor testing plus documented mount-option recommendations (spec §9.4).
4. **Plugin version mismatch across cluster nodes.** `rundeck-a` running plugin v1 and `rundeck-b` running plugin v2. Cross-repo, cross-version testing problem; operator-responsibility contract.
5. **Real performance benchmarks.** No latency/throughput targets for v1. The informal bar is "does not regress non-suspending execution throughput"; enforced by running the existing Rundeck benchmark suite pre- and post-cycle merge if the repo has one.
6. **UI automation.** The minimum UI affordance (confirm-spec §11) is verified manually. Selenium / Playwright harness for Rundeck's UI is out of scope for this cycle.
7. **Authentication/authorization at scale.** A million-execution ACL enumeration is not benchmarked; existing Rundeck authorization performance is inherited.
8. **Checkpoint blob growth under realistic data contexts.** Tests use small mock contexts. A production `dataContext` carrying large job option values could produce multi-MB blobs. Open question spec §14 Q4 — unresolved for v1.
9. **Long-duration log file tailing.** A 24-hour suspended execution with dozens of tail readers is not exercised. Open question spec §14 Q3.
10. **Resume worker scheduling under mass expiry.** 1000 eligible rows hitting a single tick. Not a v1 concern; deferred benchmark.

---

## 3. Success criteria

### 3.1 Per-invariant coverage (must have)

Every invariant in `workflow-suspend-resume.md §4` (I1 through I11) has at least one dedicated test that would fail if the invariant were violated. This is the minimum bar.

| # | Invariant | Layer | Test shape |
|---|---|---|---|
| **I1** | Single writer | L3 | Suspend + resume; assert file handle closed between the two via platform-independent check (`FileChannel.tryLock` on the file should succeed during the waiting window). |
| **I2** | Claim atomicity | L2 / race harness | N workers race to claim the same row; exactly one `affectedRows == 1`. |
| **I3** | Checkpoint completeness | L3 | Suspend + resume with a context carrying a `MockCheckpointableComponent`; assert the component's state survives the round trip. Also: suspend with a non-checkpointable component must throw `SuspensionNotAllowedException`. |
| **I4** | Single terminal write | L4 | Suspend → resume → complete; assert `dateCompleted` set once (inspect DB timestamps before/after), completion notifications fired once (mock notification service interaction count), log footer written once (grep file for `^END^`). |
| **I5** | Suspend ordering | L3 + fault injection | Inject failure at each P1–P7 point; verify expected recovery. |
| **I6** | Resume ordering | L3 + fault injection | Inject failure at each R1–R8 point; verify release-claim-and-retry behavior. |
| **I7** | No sub-workflow suspend | L1 | `SuspensionPolicy.validateSuspendable` rejects a context marked as a child. |
| **I8** | No parallel suspend | L1 | `SuspensionPolicy` rejects `ParallelWorkflowStrategy`. |
| **I9** | Abort bypasses Quartz on waiting | L2 | `abortExecutionDirect` on waiting row does NOT call `findExecutingQuartzJob` (assert on mock). |
| **I10** | No FD leakage | L3 | Before suspend: file is openable in exclusive mode by the test. After suspend but before resume: file is still openable exclusively. After resume: not openable exclusively (writer holds it). |
| **I11** | Clear on terminal | L2 | Call `ExecutionTerminalWriteService.writeTerminal` on a row that had suspend columns populated; assert all 8 columns are NULL/default after. |

### 3.2 Per-error-row coverage (must have)

Every row in `workflow-suspend-resume.md §10` error handling has at least one test.

**Suspend-time errors:**
- Parallel strategy → `SuspensionNotAllowedException` with naming message.
- Sub-workflow → `SuspensionNotAllowedException` with naming message.
- Non-checkpointable component → `SuspensionNotAllowedException` naming the class.
- Non-checkpointable writer → `SuspensionNotAllowedException` naming the plugin.
- Checkpoint serialization failure → `SuspensionFailedException`; execution transitions `failed`.
- Log flush/fsync/close failure → `SuspensionFailedException`; execution transitions `failed`.
- DB commit failure → rollback; execution stays `running`; normal failure path.

**Resume-time errors:**
- Checkpoint schema version unknown → mark `failed`, no retry.
- Context component class not loadable → release claim, increment count, retry.
- Step plugin class not loadable → release claim, increment count, retry.
- Log file missing → release claim, increment count, retry.
- Log file cannot be opened in append mode → release claim, increment count, retry.
- Checkpoint deserialization failure → mark `failed`, no retry.
- Claim returns zero rows (race lost) → skip, no error.
- Claim returns zero rows (row no longer waiting) → skip, no error.
- Resume thread crash before first step invocation → reaper recovery.
- `resume_attempt_count >= maxAttempts` → mark `failed` with `ResumeExhausted`.

**Timeout and abort:**
- Timeout expiry → synthetic payload delivered, step's `timeoutAction` fires.
- Abort on `waiting` → direct transition, log closed normally, notifications fire.

Total: 18 error-row tests required.

### 3.3 Per-walkthrough coverage (must have)

Each of the four walkthroughs in `docs/walkthroughs/workflow-suspend-resume.md` has at least one integration test that follows its critical path:

| # | Walkthrough | Test mode | Mapped test |
|---|---|---|---|
| 1 | Normal path (abstract) | L4 | `confirm_normal_path_integration_test` — covered via walkthrough 2 (abstract logical flow is equivalent to a single-node concrete flow). |
| 2 | Single-node | L3 | Full alice-bob flow on one JVM. |
| 3 | Clustered | L4 | POST on simulated node B while execution suspended on simulated node A; resume claimed by either. |
| 4 | Cluster node crash | L4 + fault injection | Main case: fault-inject a JVM-death simulation after suspend commit; verify other simulated node resumes. Plus sub-cases: crash during each P1–P7 injection point. Plus: crash during resume (R2–R5 injection points on the resuming simulated node). |

Plus operator-pause-specific scenarios (not in the four named walkthroughs but traced in `operator-pause.md §3` and §10):

| # | Scenario | Layer | Test |
|---|---|---|---|
| OP1 | Operator pause + resume on any multi-step execution | L4 | Full flow test in Wave 6. |
| OP2 | Pause + confirm sequential | L4 | Sequential-suspension-type test in Wave 6. |
| OP3 | Pause rejected on boundary-less workflow | L3 | Single-step + final-step tests in Wave 6. |
| OP4 | Pause rejected on sub-workflow | L3 | Wave 6 integration test. |
| OP5 | Cross-node pause request (node B writes flag, node A acts) | L4 | Cluster-simulating test in Wave 6. |

### 3.4 Per-decision coverage (must have)

Every locked decision in `workflow-suspend-resume.md §12` that has observable runtime behavior has a test asserting that behavior.

| Decision | Test |
|---|---|
| D1 | Canonical plugin pattern: `return context.suspend(request)` works; return-as-exception would not reach the engine's suspension handling. |
| D2 | Plugin observes `context.getResumePayload()` on resume invocation; gets null on first invocation. |
| D3 | Plugin observes `context.getSuspendMetadata()` on resume invocation with the frozen metadata from suspend time. |
| D4 | `EngineWorkflowExecutor.executeWorkflowResume` is a distinct method; called by resume worker, not `executeWorkflowImpl`. |
| D10 | `ExecutionTerminalWriteService.writeTerminal` invoked from `ExecutionJob.saveState` (normal path) AND from `ExecutionResumeService` (resume path); both produce equivalent terminal row state. |
| D14 | Config freeze: edit job definition between suspend and resume; resumed plugin reads pre-edit values. |
| D15 | Three fault-injected failed resume attempts mark execution `failed` with `ResumeExhausted`. |
| D16 | Polymorphic `ResumePayload` Jackson round-trip: `ConfirmationPayload` round-trips through `ResumePayload`-typed field with `type` discriminator. |
| D19 | Notification delivery node: verify `waiting-*` event fires from suspend-persisting node; terminal event fires from completion-writing node (may differ in cluster-simulating mode). |

### 3.5 Cycle-wide exit criteria (the 13 assertions)

Reproduced from `cycles/workflow-suspend-resume.md`. A Rundeck OSS build with all waves merged must satisfy these:

1. A workflow containing a built-in `confirm` step compiles and executes.
2. alice (with `run` ACL) can start the workflow from the UI or API.
3. The confirm step suspends cleanly; execution thread released; log file footerless; `status='waiting'`; `serverNodeUUID=NULL`.
4. `execution.waiting-confirmation` notification event fires once, consumable by existing notification plugins.
5. bob (with `confirm` ACL and any required role) can approve or deny via `POST /api/{v}/execution/{id}/confirm` on any cluster node.
6. On approval, execution resumes via a DB-polling worker claim, possibly on a different cluster node.
7. On approval, execution proceeds through remaining steps and terminates normally; `dateCompleted` set once; completion notifications fire once; log file closed with one footer.
8. An `ExecutionConfirmation` row records the confirmation event immutably.
9. On node crash while waiting, another node's resume worker claims and completes the execution.
10. On timeout, timeout sweep delivers a synthetic payload; step fails per its `timeoutAction`.
11. Abort on a waiting execution transitions directly to aborted; log file closed with footer; notifications fire.
12. `SuspensionPolicy` rejects parallel strategy, sub-workflow, non-checkpointable components, non-checkpointable writers — all with clear error messages.
13. alice can `POST /api/{v}/execution/{id}/pause` on a running multi-step execution; execution transitions to `waiting` with `suspend_metadata.type='operator-pause'` at the next step boundary; `POST /resume` continues it to completion.
14. `POST /pause` on a single-step workflow returns 409 with reason `no-remaining-boundary`.
15. A workflow containing both a confirm step and an operator-pause request in sequence resolves correctly: pause first (earlier boundary), confirm second (when its step runs), both resolved in order.
16. All unit and integration tests pass. No existing test regresses. No new flake.

### 3.6 Negative criteria (must not happen)

Tests that assert the absence of behavior, not just presence:

- **No regression of existing engine tests.** Entire pre-existing Rundeck test suite passes unchanged after merging any wave.
- **No new flakiness.** A test added in this cycle that fails intermittently is a defect and must be fixed before merge. Zero tolerance — "retry once and hope" is not acceptable.
- **No `finishWorkflowExecution` listener callback on suspended results.** Mock listener asserts zero interactions for that callback when the workflow result `isSuspended()==true`.
- **No `dateCompleted` set on entry to `waiting`.** Verified in every suspend test.
- **No completion notifications fired on entry to `waiting`.** Zero interactions against mock notification service.
- **No log `^END^` footer in a waiting execution's log file.** Verified via file content inspection (grep absence).
- **No double-execution under claim race.** Two racing workers; exactly one succeeds. Step plugin `execute` method instrumented to count invocations; count must equal 1.
- **No orphan-reaping of waiting rows.** A waiting execution that sits past the reaper's normal threshold is NOT marked incomplete. The reaper's query result set is inspected directly in a test; `waiting` row must not appear.
- **No approval event loss when suspension fails mid-commit.** If `ExecutionConfirmation` row was written before suspension was aborted, the row persists and is consistent with the final execution state.
- **No plugin state carry-over across suspend/resume.** Plugin instance field values set in the first invocation must NOT be visible in the resume invocation. Test uses a static counter on the mock plugin class to detect accidental reuse.
- **No extra terminal writes.** `ExecutionTerminalWriteService.writeTerminal` is invoked at most once per execution, even across multiple `waiting → running → waiting → running → terminal` cycles. Instrumented count must equal 1.

### 3.7 Coverage percentage is explicitly NOT a target

Line coverage is not a merge criterion. The invariant-driven + error-row-driven + walkthrough-driven + decision-driven tests are the floor; everything else is developer judgment per-change.

Coverage tools may report numbers during review for context ("this PR adds 200 lines and covers 180"), but "we hit 90% coverage" is not a merge gate. "Every invariant has a named test" is.

Rationale: coverage percentage is gameable (tests that execute code without asserting on behavior count), and a high number does not imply correctness. A named invariant check does.

---

## 4. Per-wave test deliverables

Tests are delivered with the wave that introduces the feature they exercise. A wave does not merge until its test deliverables are green.

### Wave 0 — Types and contracts

- **L1 unit** for every new type in the Wave 0 file list:
  - `SuspendRequest` Jackson round-trip (`type` discriminator absent; this is not polymorphic).
  - `ResumePayload` Jackson round-trip with `MockResumePayload` (`type="mock-test"`).
  - `SuspendedStepResult.isSuspended() == true`, `isSuccess() == false`, `getFailureReason()` returns distinctive marker.
  - `SuspensionPolicy.validateSuspendable` rejection cases (parallel, sub-workflow, non-checkpointable component, non-checkpointable writer). Passing case: default context.
  - `StepExecutionResult.isSuspended()` default returns false for all existing implementations (via a parameterized test that iterates over known subclasses).
  - `StepExecutionContext` default methods: `getResumePayload()` returns null, `getSuspendMetadata()` returns empty map, `suspend()` throws `UnsupportedOperationException`.
  - `WorkflowExecutionResult.isSuspended()` default returns false.
- **Verification task tests** for Wave 0 blockers (not strict tests, but committed-to-repo prototypes):
  - `WFSharedContextImpl` Jackson round-trip: read its current structure; commit a test that serializes and deserializes a non-empty instance. If this fails, Wave 0 escalates.
  - `UserAndRolesAuthContext` serializability: same approach. May produce a synthetic context rather than a real Spring-Security-backed one; document the delta.
  - Jackson polymorphic subtype registration across module boundary: a minimal prototype module that registers `MockResumePayload` as a subtype of `ResumePayload`; a test loads the module via ServiceLoader and asserts the subtype is resolvable in a deserializer that only knows about `ResumePayload`.

**Wave 0 exit criteria:** all L1 tests pass; verification tasks complete with either confirmed feasibility or a documented blocker.

### Wave 1 — Engine loop change

- **L1 unit:**
  - Mock step operation returns `SuspendedStepResult`; `WorkflowEngineOperationsProcessor` exits loop; returns partial results with `suspended=true` on envelope.
  - 5-step sequential workflow, step 3 suspends; steps 1–2 in completed results, step 3 as suspended, steps 4–5 absent, workflow result suspended (not failed).
  - `SuspensionPolicy` invoked from `EngineWorkflowExecutor` before `processOperations`; rejection propagates as step failure with clear reason.
  - `BaseWorkflowExecutor` suppresses `finishWorkflowExecution` listener callback on suspended result; step-level `finishWorkflowItem` / `finishExecuteNodeStep` DO fire with `isSuspended()==true` (asserted via mock listener).

**Wave 1 exit criteria:** all Wave 1 tests pass; every existing engine test passes unchanged (no regression).

### Wave 2 — Execution lifecycle + schema

- **Migration test:**
  - Forward migration applies cleanly to an empty DB and a populated DB.
  - Backward migration rolls back the 8 new columns + 2 indexes.
- **L2 Grails unit:**
  - `Execution.runningExecutionsCriteria` excludes `waiting` rows.
  - `Execution.getExecutionState()` maps `waiting` to `ExecutionState.waiting`.
  - `ExecutionService.onWorkflowSuspended` leaves `dateCompleted=null`, clears `serverNodeUUID`, sets `waitStartedAt` + `waitTimeoutAt`, writes checkpoint + metadata, fires `waiting-<type>` notification event.
  - `ExecutionService.abortExecutionDirect` on `waiting` row bypasses `findExecutingQuartzJob` (assert on mock).
  - `ExecutionService.findWaitingExecutions` returns waiting rows, filtered by project.
  - `ExecutionJob.saveState` with suspended `RunResult` skips `dateCompleted` + completion notifications (mock service zero interactions).
- **L3 integration:**
  - Mock workflow with `SuspendableMockStep` returning `SuspendedStepResult` on first invocation; full pipeline. Assert end state: `status='waiting'`, `dateCompleted=null`, `serverNodeUUID=null`, `checkpoint_data` + `suspend_metadata` populated, `wait_started_at` set, log file exists on disk, log file does NOT contain `^END^` footer, zero completion events fired.
  - Reaper tick during the test's waiting window: row not touched.

**Wave 2 exit criteria:** migration reversible; L2 tests pass; L3 mock-suspend integration passes; reaper does not touch waiting rows.

### Wave 3 — Log writer SPI hardening

- **L1 unit:**
  - `FSStreamingLogWriter.suspend()` calls `flush`, `getFD().sync`, `close` in order; does NOT call `outputFinish` (verified via spy).
  - Constructor with `resumeMode=true` suppresses `outputBegin()` call at `:63`.
  - `SuspensionPolicy` rejects a configured writer chain that includes a non-`CheckpointableStreamingLogWriter` entry; message names the offending plugin.
- **L3 integration:**
  - Open writer → write events A → suspend → `openForResume` → write events B → close normally → file has exactly one `^text/x-rundeck-log-v2.0^` header at position 0, all events A followed by all events B in order, exactly one `^END^` footer at EOF. Parseable by `FSStreamingLogReader`.
  - Same with the new writer opened on a tmpfs mount that simulates NFS (write barriers, delayed flush).
- **L5 contract tests:**
  - Abstract test class `CheckpointableStreamingLogWriterContractTest` published to a test JAR. Runs the suspend/resume file-content test against any implementation provided by the subclass.
  - Same for `CheckpointableContextComponentContractTest`.

**Wave 3 exit criteria:** all L1/L3 tests pass; contract test class compiled and testable against `FSStreamingLogWriter` itself (as a self-validation).

### Wave 4 — Resume worker + terminal write factoring

- **L2 Grails unit:**
  - `ExecutionTerminalWriteService.writeTerminal` clears all 8 suspend-related columns (I11), sets `dateCompleted`, fires completion notifications, closes log writer with footer.
  - `ExecutionResumeService.markResumeReady` returns false if `status != 'waiting'`.
  - `ExecutionResumeService.scheduledResumePoll` queries, claims atomically, invokes `resumeExecution`.
  - `ExecutionResumeService.scheduledTimeoutSweep` finds expired rows and writes synthetic timeout payloads.
  - Claim race (L2 race harness): two workers simultaneously; exactly one `UPDATE` returns 1 row.
  - Resume failure (fault-injected rehydration error): release claim, increment `resume_attempt_count`.
  - Resume exhaustion: `resume_attempt_count >= maxAttempts` marks execution `failed` with `ResumeExhausted`.
- **L3 integration:**
  - Full single-node suspend/resume: mock suspendable step → suspend → resume worker claims → completes → `ExecutionTerminalWriteService` writes terminal state, log file has footer, all invariants hold.
- **L4 cluster-simulating integration:**
  - Suspend on "node A" context, resume on "node B" context (via cluster-simulator harness forcing fresh rehydration).
  - Log file integrity across the simulated cross-node handoff.
  - Terminal write happens on the resuming context.
- **Fault-injection integration:**
  - P1 through P7 (suspend-side) and R1 through R8 (resume-side) each get an integration test. 15 tests.
- **Race harness integration:**
  - Claim race with real DB serializability.
  - Rapid oscillation: suspend → resume → suspend → resume within 100ms; verify no corruption, correct sequence of events, `last_resumed_at` updates correctly.
  - Abort during suspend-commit race.

**Wave 4 exit criteria:** all the above pass; end-to-end single-JVM suspend/resume works; cluster-simulating mode works; fault injection recovers per spec §10; no orphaned resource handles after any test.

### Wave 5 — First-class confirm plugin

- **L1 unit:**
  - `ConfirmWorkflowStep` first-invocation constructs `SuspendRequest` with correct metadata (message interpolated, decisionSet, timeoutMs, timeoutAction, requiredConfirmerRoles).
  - `ConfirmWorkflowStep` resume-invocation branches: `approve` → success; `deny` → `StepException(ConfirmDenied)`; `timeout + timeoutAction=deny` → `StepException(ConfirmTimeout)`; `timeout + timeoutAction=approve` → success with log message; `timeout + timeoutAction=fail` → `StepException(ConfirmTimeoutFailure)`.
  - Config freeze: plugin reads from `context.getSuspendMetadata()` on resume, NOT from `item.getProperties()`; verified by setting different values and confirming the frozen ones are used.
  - `ConfirmationPayload` Jackson round-trip with `type="confirmation"` discriminator.
  - Polymorphic deserialization: a `ResumePayload`-typed field deserializes a `ConfirmationPayload` correctly via subtype registration.
- **L2 Grails unit:**
  - `ExecutionConfirmation` domain immutability: updates rejected.
  - `ExecutionConfirmation` can have multiple rows per execution (multiple confirm steps in one workflow).
  - `ApiConfirmController.confirm` happy path → 200 + row inserted + `markResumeReady` called.
  - `ApiConfirmController.confirm` 409 on `status != 'waiting'`.
  - `ApiConfirmController.confirm` 409 on wrong `suspend_metadata.type`.
  - `ApiConfirmController.confirm` 403 on missing `confirm` ACL.
  - `ApiConfirmController.confirm` 403 on missing required role.
  - `ApiConfirmController.confirm` 400 on decision not in decisionSet.
  - `ApiConfirmController.status` returns correct `callerCanConfirm` for authorized vs. unauthorized callers.
  - `ApiConfirmController.confirmations` returns rows in `confirmedAt` ASC order; enforces `read` ACL.
  - New ACL `confirm` action recognized by policy engine.
  - `execution.waiting-confirmation` notification event fires on suspend with `type=confirmation` metadata; does NOT fire for non-confirmation suspensions; does NOT fire on resume or completion.
- **L3 single-node integration** (using `AliceBobCarolScenario` fixture):
  - **Full approve flow:** alice starts `prod-deploy-v4` → build succeeds → confirm suspends → bob POSTs `/confirm approve` → deploy runs → smoke-test runs → success. Assert: one `ExecutionConfirmation` row with `decision=approve`, `timeout=false`; execution `status=succeeded`; `dateCompleted` set; log file has one footer; completion notifications fired once.
  - **Full deny flow:** same, but `decision=deny`. Assert: deploy step NEVER runs. `ExecutionConfirmation` row written. Execution ends `status=failed`.
  - **Timeout-deny flow:** configure `timeout=5s`. Don't POST anything. Assert: synthetic `ExecutionConfirmation` row with `timeout=true`. Execution ends `status=failed`.
  - **Timeout-approve flow:** same but `timeoutAction=approve`. Assert: deploy runs; execution succeeds; synthetic confirmation row.
  - **Unauthorized POST:** carol (no `confirm` ACL) → 403 Forbidden; execution remains `waiting`; no confirmation row.
  - **Concurrent POST race:** two threads POST `approve` and `deny` simultaneously. Exactly one returns 200; the other returns 409. Winning decision matches final outcome.
  - **API-only confirmation (no UI):** drive the full confirm flow via HTTP client only (no UI involvement). Verifies the API-first design (`confirm-workflow-step.md §8`). Authenticate via API token, POST `/confirm`, assert response envelope, assert `ExecutionConfirmation` row, assert execution continues. Proves scripts/automation can drive confirmation without any UI assumption.
- **L4 cluster-simulating integration:**
  - Two-node cluster: POST on simulated node B while execution suspended on simulated node A; resume claimed by either. Assert: `ExecutionConfirmation` row was written by the node that received the POST; execution completion writes happen on the node that claimed the resume (may differ).
  - Config freeze across node boundary: edit `message` in the job definition between suspend and resume; assert resumed plugin sees pre-edit message from frozen metadata.

**Wave 5 exit criteria:** every cycle-wide exit criterion (3.5, items 1–13) is satisfied; the 15 integration scenarios above pass; manual UI verification checklist (§7) completed.

### Wave 6 — First-class operator-pause feature

- **L1 unit:**
  - `OperatorResumePayload` Jackson round-trip with `type="operator-resume"` discriminator.
  - `OperatorPauseHook.evaluate` returns `SynthesizeSuspension` when `pause_requested = true` AND workflow has remaining steps.
  - `OperatorPauseHook.evaluate` returns `ProceedToNextStep` when `pause_requested = false`.
  - `OperatorPauseHook.evaluate` returns `ProceedToNextStep` when the just-completed step is the final step (no boundary).
  - Engine hook dispatch: registered hooks are invoked after each step's result in `EngineWorkflowExecutor`'s aggregation loop; their returned `SynthesizeSuspension` converts into a normal suspend path.
  - Performance regression: non-paused execution path with `OperatorPauseHook` registered does not regress measurably compared to pre-Wave-6 baseline. Microbenchmark on a 10-step mock workflow.

- **L2 Grails unit:**
  - `ApiOperatorPauseController.pause` happy path + 404 (not found) + 409 (not running, sub-workflow, no-remaining-boundary) + 403 (missing `pause` ACL).
  - `ApiOperatorPauseController.resume` happy path + 409 (not waiting, not operator-pause type) + 403.
  - `ApiOperatorPauseController.pause/status` returns correct state in both running-with-pending-pause and waiting-after-pause scenarios.
  - ACL `pause` action enforced by policy engine.
  - `execution.waiting-operator-pause` event fires on synthesis; does NOT fire on normal suspensions; does NOT fire on resume.
  - `pause_requested` flag cleared on successful suspend-synthesis (atomic with the status transition).
  - `pause_requested` flag cleared on terminal transition per I11 (even if never acted on).
  - Integration with `ExecutionTerminalWriteService`: clearing all suspend columns includes `pause_requested`.

- **L3 integration (using `AliceBobCarolScenario` fixture):**
  - **Full multi-step pause/resume flow:** alice starts `prod-deploy-v4` → step 1 (build) begins → alice `POST /pause` → build completes → engine synthesizes operator-pause → execution `status=waiting`, `suspend_metadata.type='operator-pause'` → alice `POST /resume` → step 2 (confirm) begins → (also suspends for confirmation) → bob approves → deploy + smoke-test run → success.
  - **Single-step workflow:** job with one step; `POST /pause` returns 409 `no-remaining-boundary`; execution proceeds normally and completes.
  - **Sub-workflow pause:** workflow invokes a child job; child's execution receives `POST /pause` → 409 `sub-workflow`; child proceeds normally.
  - **Pause during final step:** workflow of 3 steps; wait until step 3 is running; `POST /pause` → 409 `no-remaining-boundary`.
  - **Pause on waiting execution:** execution already waiting (either confirmation or operator-pause); `POST /pause` → 409 `not-running`.
  - **Resume on confirmation-type row:** execution waiting due to confirm step; `POST /resume` → 409 `not-operator-pause`.
  - **Two racing `POST /pause`:** idempotent flag write; both return 200; engine synthesizes exactly one suspension.
  - **Cluster-simulating two-node:** pause request on simulated node B while execution runs on simulated node A; engine hook on node A reads the flag, synthesizes; resume from either node.
  - **Abort of an operator-paused execution:** `POST /kill` on a waiting row transitions directly to aborted; log closed normally; notifications fire; execution ends `status=aborted`.
  - **Pause + confirm sequential scenario** (from `operator-pause.md §10`): 10-step flow where pause happens between steps 1 and 2, then confirm happens at step 2; both suspend types observed in sequence; workflow completes.
  - **API-only pause (no UI):** drive the full pause/resume flow via HTTP client only. Authenticate via API token, POST `/pause`, poll `/pause/status`, POST `/resume`, assert execution completes. Verifies API-first design.

**Wave 6 exit criteria:** every operator-pause test above passes; cycle-wide exit criteria items 13, 14, 15 (from §3.5) are satisfied; manual UI verification of pause/resume buttons on the execution detail page.

### Wave 7 — Reference-consumer dogfooding (optional)

No strict test deliverables — this is a validation vehicle, not a shipping wave. Aspirational tests if time permits:
- Build the ported `RxConfirmWorkflowStep` against the Rundeck SDK published from this cycle.
- Manual end-to-end test: the ported step suspends and resumes on approval.

---

## 5. Known gaps in the test strategy

Honest inventory of where the test plan is weaker than ideal. Reviewers should know these exist so they can decide whether to accept or push back.

1. **No real two-node CI.** Cluster-simulating mode catches most cross-node issues by forcing fresh rehydration, but it does NOT catch real NFS close-to-open consistency bugs, real cluster Grails session/security context differences between JVMs, or real Jackson classloader issues across plugin module boundaries in a multi-JVM deployment. Mitigation: manual release-checklist verification on a real two-node setup (§7).
2. **Jackson polymorphic subtype registration is verified by prototype, not production testing.** Wave 0's verification task validates feasibility; after that, subsequent changes to plugin modules that add new subtypes could regress without a dedicated test. Mitigation: add a "registered subtypes regression test" that enumerates all known `ResumePayload` subtypes and asserts each round-trips. Not yet written.
3. **Plugin-version-mismatch scenarios are not automated.** If `rundeck-a` has plugin v1 and `rundeck-b` has plugin v2, and an execution suspends under v1 then resumes on v2, the resume reads frozen metadata written by v1. We don't have a test for that. Cross-repo testing problem.
4. **Log file growth under long suspension.** No test verifies that an execution suspended for simulated hours with many log-file tails doesn't leak file descriptors or slow down. Spec §14 Q3 notes this; no test coverage.
5. **Checkpoint blob size under realistic data contexts.** Tests use small mock contexts. A real `dataContext` carrying large job option values could produce multi-MB blobs that stress DB write performance. Spec §14 Q4 notes this; no benchmark.
6. **Resume worker scheduling under load.** Tests fire a single resume per test. The worker's behavior when 1000 eligible rows hit a single tick (e.g., mass timeout expiry) is untested. Not a v1 concern but worth a follow-up cycle benchmark.
7. **`BaseWorkflowExecutor` event-firing paths not fully enumerated.** We assert `finishWorkflowExecution` doesn't fire, but there may be other listener callbacks we haven't enumerated. Wave 1 needs a read-then-test: enumerate all listener callbacks that `BaseWorkflowExecutor` fires in the terminal path, add an assertion for each. This is a Wave 1 deliverable; adding to exit criteria.
8. **No test for UI rendering.** The minimum UI affordance is verified by manual release checklist, not automated testing.
9. **Fault injection harness is new infrastructure.** Wave 4 must ship the `FaultInjector` harness alongside the tests that use it. If the harness itself is buggy, tests that use it may false-pass. Mitigation: a small "injector self-test" that injects a known failure and verifies the injection actually happened (sanity test).
10. **`ConfirmationPayload` Jackson subtype registration tested via ServiceLoader**, but Rundeck's actual plugin classloader behavior may differ from the test harness's classloader. Wave 0 prototype must use the real classloader setup, not a simplified test harness.

---

## 6. Open questions for Phase 2 review

1. **Should we invest in a real two-node CI harness?** Spinning up two Grails processes + shared tmpdir + shared Postgres in Docker Compose is feasible but adds ~1 minute to CI. Worth it for the confidence boost on cluster correctness. Alternative: test only in cluster-simulating mode, accept the residual risk, handle real-cluster bugs as post-merge fixes.
2. **What is the definition of "no flake"?** Zero-tolerance is ideal but every test suite has some flake floor. Proposal: any new test in this cycle that fails twice in CI within its first month of existence is reverted and rewritten. Acceptable?
3. **Should coverage percentage be reported at all in PR review?** Currently the plan says "reported for context, not a gate." If it's not a gate, should we even compute it? Proposal: yes, compute for trend awareness, but the gate is the named-invariant check.
4. **Fault injection as a separate gradle test task or intermixed with normal integration tests?** Separate is cleaner (CI can run them in parallel); intermixed is simpler (no second test task to maintain). Leaning separate.
5. **Do we need a `test-plan` artifact in spec-cycle methodology going forward?** This document exists because consolidating made review easier, but it duplicates content from spec §11 and cycle manifest. Going forward: either keep test plan as a standing artifact type (extend spec-cycle practice) or treat this as a one-off for complex cycles.

---

## 7. Manual release checklist (not automated)

Verified on a real two-node Rundeck deployment before any release that includes this cycle. This is a manual pre-release gate, not CI.

### 7.1 Environment setup

- [ ] Two Rundeck OSS nodes configured as a cluster: `rundeck-a.prod`, `rundeck-b.prod`.
- [ ] Shared Postgres DB accessible from both nodes.
- [ ] Shared NFSv4 mount at `/var/lib/rundeck/logs` with `actimeo=0` (or equivalent CTO-consistent mount).
- [ ] Load balancer in front of both nodes (round-robin or session-sticky — test both).
- [ ] `confirm-plugin` module deployed to both nodes at the same version.
- [ ] ACL policy file with `alice` (run), `bob` (run+confirm+sre-role), `carol` (read-only).
- [ ] Test job `ops-deploy:prod-deploy-v4` with 4 steps including the `confirm` step at position 2.

### 7.2 Scenarios to verify manually

- [ ] **Single-node full flow on `rundeck-a` only** (`rundeck-b` stopped): alice runs job, build runs, confirm suspends, bob gets notification, bob approves, deploy runs, succeeds. Confirmation row exists, log file has one header + footer.
- [ ] **Two-node normal flow:** both nodes running. alice runs on `rundeck-a`. bob's browser lands on `rundeck-b` via LB. bob approves. Execution resumes on whichever node claims first. Succeeds.
- [ ] **Two-node with `rundeck-a` killed after suspend:** alice runs on `rundeck-a`, confirm suspends, kill `rundeck-a`'s JVM hard. bob approves via `rundeck-b`. `rundeck-b` claims and completes the deploy. Succeeds.
- [ ] **Timeout flow:** configure `timeout=2m`, don't approve. After 2 minutes, synthetic timeout fires per `timeoutAction`. Execution ends in expected state.
- [ ] **Authorization negative:** carol attempts to POST `/confirm` → 403 Forbidden. Verify in Rundeck's audit log that the authorization denial was recorded.
- [ ] **Config freeze:** alice starts job → confirm suspends → alice edits the confirm step's `message` in the job definition → bob approves → verify the log shows the pre-edit message (from frozen metadata).
- [ ] **Log file inspection after completion:** open the execution's log file directly on the shared FS. Verify: starts with `^text/x-rundeck-log-v2.0^`, contains events in chronological order spanning both pre-suspend and post-resume periods, ends with exactly one `^END^`.
- [ ] **Database inspection:** execute `SELECT status, date_completed, server_node_uuid, checkpoint_data, suspend_metadata, resume_ready, resume_payload, wait_started_at, wait_timeout_at, last_resumed_at, resume_attempt_count FROM rundeck_execution WHERE id = ?` on the completed execution. Verify all suspend-related columns are NULL per I11.
- [ ] **Audit trail:** `SELECT * FROM rundeck_execution_confirmation WHERE execution_id = ?` returns the expected row(s).
- [ ] **Notification delivery:** verify the email/Slack/webhook consumer received the `waiting-confirmation` event and the later `succeeded` event. Inspect the event payload node UUID — may differ between the two events (walkthrough gap G32 resolution).
- [ ] **Operator-pause two-node flow:** alice starts `prod-deploy-v4` on `rundeck-a`. While the build step is running, alice clicks "Pause" in the UI on `rundeck-b` (via LB). Execution transitions to `waiting` at the next boundary. alice clicks "Resume" on either node. Execution completes. Verify `waiting-operator-pause` notification fired.
- [ ] **Operator-pause single-step rejection:** job with one step; attempt pause via UI → error message indicates `no-remaining-boundary`; execution proceeds normally.
- [ ] **Operator-pause + confirm sequential:** long-running job; pause between step 1 and 2; resume; confirm step then suspends on its own; bob approves; execution completes. Verify both suspensions recorded correctly in status history.
- [ ] **API-only pause:** pause + resume flow entirely via curl (no UI). Verifies the API-first design for scripts and automation.
- [ ] **Pause ACL enforcement:** carol (no `pause` ACL) attempts `POST /pause` → 403 Forbidden. Verify no state change.

### 7.3 Load characteristics to observe (not asserted, just recorded)

- [ ] Resume-worker DB load at 2s polling interval with 0, 10, 100, 1000 waiting executions. Record CPU and DB query latency.
- [ ] Log file tailing during a long waiting period — open the UI's live-tail view on a waiting execution for 5 minutes; verify no memory growth on either node.
- [ ] Memory footprint of a waiting execution's checkpoint blob with a realistic `dataContext` size (populated from a real job with 10+ option values).

---

## 8. Cross-references

- `docs/specs/workflow-suspend-resume.md §4` — invariants I1–I11 (authoritative).
- `docs/specs/workflow-suspend-resume.md §10` — error handling rows (authoritative).
- `docs/specs/workflow-suspend-resume.md §11` — test strategy (authoritative; this document reorganizes and extends).
- `docs/specs/workflow-suspend-resume.md §12` — locked decisions (authoritative).
- `docs/specs/confirm-workflow-step.md §13` — confirm plugin test strategy (authoritative; this document reorganizes).
- `docs/walkthroughs/workflow-suspend-resume.md` — scenarios to exercise end-to-end.
- `docs/cycles/workflow-suspend-resume.md` — cycle-wide exit criteria + per-wave deliverables (authoritative).

If this document contradicts any of the above, the source is correct and this document is wrong — file a defect against this document.

---

**⏸ PAUSE for Phase 2 review.** Review this test plan alongside the spec artifacts. Confirm whether the coverage is adequate, the layering is sensible, and the known gaps (§5) are acceptable. Wave 0 verification tasks may begin after approval.
