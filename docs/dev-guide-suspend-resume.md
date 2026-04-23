# Developer Guide: Suspend/Resume + Confirm Step

**Audience:** Developers and AI agents working on the workflow suspend/resume feature and confirm step plugin.

Read `docs/confirm-pause-resume-spec.md` first for the full as-built specification. This guide covers the practical development workflow and lessons learned.

---

## 1. Architecture overview

A step plugin calls `context.suspend(request)` which stores a `SuspendedStepResult` in a `pendingSuspension` field. `StepPluginAdapter` detects it and returns the suspended result to the engine. The engine rule `STEP_SUSPENDED_END_WORKFLOW` ends the workflow loop. `ExecutionJob` detects `thread.isSuspended()`, suspend-closes the log writer (no `^END^` footer), and persists `status='waiting'` with a checkpoint blob. A `@Scheduled` polling worker in `ExecutionResumeService` (2s tick) claims the execution via atomic DB update, rehydrates the context, reopens the log writer in append mode, and calls `EngineWorkflowExecutor.executeWorkflowResume()`.

### Key files

**Engine (core):**
- `EngineWorkflowExecutor.java` — `executeWorkflowResume()` with state replay + direct `executeWFItem` for Phase A and Phase B
- `WorkflowExecutionStateListenerAdapter.java` — `replayCompletedStep()` for state replay after resume
- `ExecutionContextImpl.java` — `pendingSuspension`, `resumePayload`, `suspendMetadata`, `pauseCheckSupplier`
- `StepPluginAdapter.java` — detects `pendingSuspension` after plugin returns
- `StepCallable.java` — operator-pause check before step invocation
- `BaseWorkflowExecutor.java` — `BaseWorkflowExecutionResult` with suspend fields, listener suppression
- `core/.../suspend/` package — `SuspendRequest`, `ResumePayload`, `ConfirmationPayload`, `SuspendedStepResult`, `ExecutionCheckpoint`, etc.

**Confirm plugin (`plugins/confirm-plugin/`):**
- `ConfirmWorkflowStep.java` — step plugin: first invocation suspends, resume invocation reads payload and exports output variables (`${confirm.decision}`, `${confirm.comment}`, `${confirm.confirmedBy}`, `${confirm.confirmedAt}`)
- `ConfirmUIPlugin.java` — UIPlugin that injects JS/CSS at `execution/show`
- `resources/js/confirm-execution.js` — polls execution status, renders approval card, handles approve/deny via jQuery AJAX with CSRF tokens
- `resources/css/confirm-execution.css` — minimal overrides, inherits Rundeck's native card/btn styles

**Grails services:**
- `ExecutionResumeService.groovy` — polling worker, resume execution, `markResumeReady()`, timeout sweep
- `ExecutionService.groovy` — `onWorkflowSuspended()`, `EXECUTION_WAITING` constant
- `ExecutionUtilService.groovy` — `suspendExecution()` with recursive `MultiLogWriter` traversal
- `LogFileStorageService.groovy` — `getLogFileWriterForResume()` (append mode)
- `FSStreamingLogWriter.groovy` — `CheckpointableStreamingLogWriter`, `suspend()`, `resumeMode`

**Controllers:**
- `ApiConfirmController.groovy` — POST /confirm, GET /confirm/status, GET /confirmations
- `ApiOperatorPauseController.groovy` — POST /pause, POST /resume, GET /pause/status

**Domain/migrations:**
- `Execution.groovy` — 9 new fields (checkpointData, suspendMetadata, waitStartedAt, etc.)
- `ExecutionConfirmation.groovy` — audit trail domain
- `SuspendResume-6.0.groovy`, `ExecutionConfirmation-6.0.groovy` — Liquibase migrations

**UI:**
- `activityList.vue` — waiting status: orange pause icon, static "Waiting for confirmation" progress bar
- `show.gsp` — hardcoded panel REMOVED, replaced by UIPlugin in the confirm-plugin JAR

---

## 2. Build-test cycle

### What changes require what builds

| Changed | Build command | WAR rebuild? | Restart? |
|---|---|---|---|
| Core Java (`core/`) | `bootWar` | Yes | Yes |
| Grails services/controllers (`rundeckapp/`) | `bootWar` | Yes | Yes |
| Grails views (`.gsp`) | `bootWar` | Yes | Yes |
| Vue components (`rundeckapp/grails-spa/`) | `bootWar` | Yes | Yes |
| Confirm plugin (`plugins/confirm-plugin/`) | Plugin JAR only | No | Yes |

### Build commands

All builds require Java 17:
```bash
export JAVA_HOME=/usr/local/opt/openjdk@17
export PATH="$JAVA_HOME/bin:$PATH"
```

**Full WAR rebuild** (~2 minutes):
```bash
./gradlew rundeckapp:bootWar -x test
```

**Plugin JAR only** (~5 seconds):
```bash
./gradlew :plugins:confirm-plugin:clean :plugins:confirm-plugin:build -x test
cp plugins/confirm-plugin/build/libs/rundeck-confirm-plugin-6.0.0-SNAPSHOT.jar rundeckapp/build/libs/libext/
```

**Both together:**
```bash
./gradlew rundeckapp:bootWar :plugins:confirm-plugin:clean :plugins:confirm-plugin:build -x test
cp plugins/confirm-plugin/build/libs/rundeck-confirm-plugin-6.0.0-SNAPSHOT.jar rundeckapp/build/libs/libext/
```

**Verify plugin JAR contents** (should include classes AND resources):
```bash
jar tf plugins/confirm-plugin/build/libs/rundeck-confirm-plugin-6.0.0-SNAPSHOT.jar
# Must contain: ConfirmWorkflowStep.class, ConfirmUIPlugin.class, resources/js/confirm-execution.js, resources/css/confirm-execution.css
```

### Kill → Start → Wait

```bash
pkill -f "rundeck-6.0.0-SNAPSHOT.war" 2>/dev/null
sleep 2
java -jar rundeckapp/build/libs/rundeck-6.0.0-SNAPSHOT.war &

# Wait (~90s):
for i in $(seq 1 24); do
  CODE=$(curl -s -o /dev/null -w "%{http_code}" http://localhost:4440/api/14/system/info 2>/dev/null)
  if [ "$CODE" = "403" ] || [ "$CODE" = "200" ]; then echo "Up"; exit 0; fi
  sleep 5
done
```

Runs on port **4440**. RDECK_BASE is `rundeckapp/build/libs/`. Admin login: `admin`/`admin`.

### API testing

```bash
# Get API token
COOKIE_JAR=$(mktemp)
curl -s -c "$COOKIE_JAR" -b "$COOKIE_JAR" -L -d "j_username=admin&j_password=admin" "http://localhost:4440/j_security_check" -o /dev/null
T=$(curl -s -b "$COOKIE_JAR" -X POST "http://localhost:4440/api/41/tokens" -H "Content-Type: application/json" -H "Accept: application/json" -d '{"user":"admin","roles":"*","duration":"2h"}' | python3 -c "import sys,json; print(json.load(sys.stdin).get('token',''))")
rm -f "$COOKIE_JAR"

# API helper
B="http://localhost:4440"
api() { curl -s -H "X-Rundeck-Auth-Token: $T" -H "Accept: application/json" "$@"; }

# E2E: create project, create job, run, wait for waiting, approve, check states
api -X POST "$B/api/41/projects" -H "Content-Type: application/json" -d '{"name":"test","config":{"resources.source.1.type":"local"}}'
# ... (see confirm-pause-resume-spec.md §2 for the full verified flow)
```

### Key paths

| Path | Purpose |
|---|---|
| `rundeckapp/build/libs/server/logs/rundeck.log` | Server log (errors, resume worker, etc.) |
| `rundeckapp/build/libs/var/logs/rundeck/<project>/job/<jobid>/logs/<execid>.rdlog` | Execution log file |
| `rundeckapp/build/libs/var/logs/rundeck/<project>/job/<jobid>/logs/<execid>.state.json` | Persisted step state |
| `rundeckapp/build/libs/server/data/grailsdb.mv.db` | H2 database |
| `rundeckapp/build/libs/libext/` | Runtime plugin JARs |
| `rundeckapp/build/libs/etc/framework.properties` | Framework config (port, etc.) |

---

## 3. Important rules

1. **Always use Java 17** for both build and run.
2. **Do NOT delete the H2 database** — Rundeck generates it on first boot via Liquibase migrations and preserves state (projects, jobs, executions) across restarts.
3. **Plugin changes need `clean` before `build`** — Gradle's up-to-date check can miss resource changes. Always use `:plugins:confirm-plugin:clean :plugins:confirm-plugin:build`.
4. **The user tests in the browser at http://localhost:4440** — provide job URLs when asking them to test.
5. **Browser cache** — the user may need hard-refresh (Cmd+Shift+R) after plugin JS/CSS changes.

---

## 4. Lessons learned (pitfalls to avoid)

These are bugs we hit and fixed. A new developer or agent should understand these to avoid repeating them.

### Engine behavior

1. **Result set ordering is non-deterministic.** The rule-based `WorkflowEngineOperationsProcessor` doesn't guarantee step result order. The suspended step may appear BEFORE completed steps in `result.getResultSet()`. Never assume position — filter by `isSuspended()` and count non-suspended results.

2. **`WFSharedContext.with(original)` creates a wrapper, not a reference.** Writes go to the wrapper's map, not the base. Data written by a plugin in Phase A isn't visible to Phase B unless explicitly merged back:
   ```java
   executionContext.getSharedDataContext().merge(resumeCtx.getSharedDataContext());
   ```

3. **`finishWorkflowItem` skips state notifications for node-dispatch steps.** Use `replayCompletedStep()` (which fires node-level events directly) instead of `beginWorkflowItem`/`finishWorkflowItem` for synthetic state replay.

4. **Phase B cannot use `executeWorkflowImpl`.** The rule engine generates start conditions like `after.step.2 == 'true'` which can't resolve in a sub-workflow context. Use direct `executeWFItem` calls instead.

### Log writer

5. **The log writer chain must handle `MultiLogWriter` fan-outs.** The standard chain is `ExecutionLogWriter → LoglevelThresholdLogWriter → MultiLogWriter → DisablingLogWriter → FSStreamingLogWriter`. The `suspendExecution` traversal uses `findAndSuspendCheckpointableWriter()` which recursively handles both `FilterStreamingLogWriter` delegates and `MultiLogWriter` fan-outs.

6. **Resume needs the full log writer chain.** Without all of these, resumed step output either goes to server log only, lacks `stepctx` metadata, or includes verbose framework messages:
   - `LoglevelThresholdLogWriter` — filters DEBUG/VERBOSE (e.g., `[workflow] Begin step:`)
   - `ContextLogWriter` + `ContextManager` — stamps events with `stepctx` metadata
   - `LoggerWithContext` — bridges to `ExecutionLogger`
   - Thread-bound stdout/stderr — captures command output (e.g., `echo "Step 3 done"`)
   - `LogFlusher` listeners — flush after node steps

### UIPlugin / browser integration

7. **UIPlugin JS must use jQuery, not fetch.** Rundeck pages already load jQuery. The `fetch` API has issues with Rundeck's session/CSRF handling.

8. **CSRF tokens are required for POST requests from browser sessions.** Read the token from the existing `exec_cancel_token` `g:jsonToken` element on the execution/show page. Inject as `X-RUNDECK-TOKEN-KEY` / `X-RUNDECK-TOKEN-URI` headers via jQuery `beforeSend`.

9. **GET requests need `X-Rundeck-Ajax: true`.** Without this header, Rundeck's API interceptor returns 404 for session-based API requests.

10. **UIPlugin CSS should inherit Rundeck's native styles.** Use `card`, `card-header`, `card-content`, `btn btn-success`, `btn btn-danger`, `form-control` classes. Don't define custom colors.

### Hibernate / database

11. **Raw SQL claims require `execution.refresh()`.** After `attemptClaim` updates the DB via HQL, Hibernate's cache is stale. Call `execution.refresh()` after `Execution.get(id)`.

12. **Wrap the entire resume path in a single `withNewSession`.** Multiple nested sessions cause `LazyInitializationException` when objects from one session are accessed in another.

13. **Don't use `saveExecutionState` from the resume path.** It opens a nested transaction that causes version conflicts. Write terminal state directly in the same session.

14. **Call `logExecution()` after terminal write.** Without this, resumed executions don't appear in the activity/history page (it reads from `base_report`, not `execution`).

---

## 5. Adding new HIL primitives

The suspend/resume infrastructure is primitive-agnostic. To add a new primitive (e.g., `choose`, `ask`, `review`):

1. **New `ResumePayload` subtype** — register via `@JsonSubTypes` on `ResumePayload.java`
2. **New step plugin** in `plugins/` — calls `context.suspend()` on first invocation, reads payload on resume
3. **New UIPlugin + JS** in the same plugin JAR — renders the appropriate input widget at `execution/show`
4. **Output variables** — export response data to `${primitive.field}` for downstream steps via both `outputContext.addOutput()` and `context.getSharedDataContext().merge()`
5. **API endpoints** — can reuse the existing confirm controller pattern, or generalize to a single `/input` endpoint that dispatches by metadata type

No engine, checkpoint, resume worker, log writer, or state model changes needed. Each primitive is a self-contained plugin JAR.

### UI ownership convention

Multiple plugins may inject JS at `execution/show` that could render a confirm panel. To prevent duplicates, all confirm UI plugins follow this DOM convention:

- **Stamp:** When a plugin renders its panel, it sets `data-rundeck-confirm-ui="true"` on the panel's root element.
- **Check:** Before rendering, a plugin calls `document.querySelector('[data-rundeck-confirm-ui]')`. If an element exists, another plugin already owns the space — skip rendering.

This means whichever plugin's JS runs first claims the space. The built-in confirm plugin acts as the fallback; third-party plugins (like rx-confirm) that load alongside it will claim the space if they run first. No plugin needs to know the names of other plugins — just check the DOM attribute.
