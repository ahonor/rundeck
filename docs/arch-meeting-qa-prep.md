# Q&A prep — Rundeck architecture meeting

**Personal reference — not shared.** Anticipated questions with prepared answers. Glance at if a question matches; speak in your own words, don't read.

**Confidence tags:**
- 🟢 **Strong** — direct answer ready
- 🟡 **Soft** — partial answer, expect follow-ups
- 🔴 **Defer** — say *"I don't have a number; let me trace through and get back to you"*

---

## Backward compatibility & impact

**"Does this break existing executions?"** 🟢
- All new code paths are opt-in. Suspend triggers only if a plugin calls `context.suspend()` or operator hits pause.
- No change to existing engine behavior for non-suspending workflows.
- Schema migration adds columns; doesn't modify existing ones. New columns NULL for old executions.
- Existing job suite passes on the fork.

**"Plugins that don't know about suspend — what happens?"** 🟢
- Continue to work as before. The suspend mechanism is invoked only when a plugin actively requests it.
- `StepPluginAdapter` adds a check after step execution: `if (pendingSuspension != null)`. Null check on every step — negligible cost. For plugins that don't suspend, behavior is identical.

**"What's the performance impact on the hot path?"** 🟡
- Zero-cost when no suspension occurs — single null check at end of step.
- Checkpoint serialization happens only at suspend time. Typical size <50KB JSON.
- 🔴 If asked for specific numbers: *"Haven't benchmarked. Happy to set that up."*

## API stability

**"Once these SPIs are public, can we change them?"** 🟢
- All new types annotated `@Experimental`. Explicit signal that shape may change.
- Lean: keep `@Experimental` for at least one full release cycle, gather plugin-author feedback.
- Backward-incompatible change later possible with deprecation cycle.
- Highest-risk surface is the Jackson-serialized payloads (on-wire data contracts).

**"How long do you keep `@Experimental`?"** 🟡
- Open question — flagged in the brief.
- Lean: at least one minor release, ideally two.
- Risk of premature stabilization is real given 6 of 7 primitives are still designed-for-not-built.

## Schema migration

**"What columns are added?"** 🟢
- `server_nodeuuid` — atomic claim coordination
- `suspended_at`, `wait_started_at`, `suspend_deadline` — timing
- `resume_ready` (bool) — polling worker flag
- `resume_payload` (text/JSON) — serialized payload
- `resume_attempt_count` (int) — observability
- New `execution_confirmation` table for audit rows.

**"Multi-DB compat?"** 🟡
- Tested on H2 (dev) plus [confirm which production DBs you exercised — MySQL? Postgres?].
- All columns use standard types; no DB-specific features.
- JSON payload uses TEXT — portable across all targets.

**"Rollback?"** 🟡
- Liquibase migration has a documented downgrade path (drop new columns).
- Not strictly automated; manual intervention if needed.
- Forward-compatible: old code reading new schema sees NULL columns and behaves as not-suspended.

## HA / multi-node

**"Atomic claim race conditions?"** 🟢
- Atomic via `UPDATE ... SET serverNodeUUID=me WHERE serverNodeUUID IS NULL`. DB-level race resolution.
- Suspend writes `serverNodeUUID = NULL` → any node can pick up.
- Tested with concurrent claim attempts across two nodes; no double-execution.

**"What if Rundeck restarts mid-resume?"** 🟢
- Suspended executions persist with status `'waiting'`.
- On restart: no recovery action needed; they sit until resume signal or timeout.
- Polling worker on each node atomically claims ready-to-resume work.
- Tested: kill mid-suspend, restart, verify still resumable.

**"Stuck-in-progress claims (node dies mid-resume)?"** 🟡
- Heartbeat timeout for stuck claims is **not yet implemented**. Flagged in the brief.
- Today: if a node dies after claim but before completing resume, execution stays in `serverNodeUUID = node-X`, status `'running'` — looks in-progress but isn't.
- Mitigation worth adding for v1: heartbeat timestamp + reaper task.

## Restart recovery

**"Pending suspends on Rundeck restart?"** 🟢
- Persist in DB. No recovery action on startup.
- Resume worker picks them up when ready (atomic claim resolves which node).
- Tested.

## Log writer compat

**"Third-party log writers without checkpoint support?"** 🟢
- Recursive walk of writer chain at suspend time. Handles `FilterStreamingLogWriter` chains and `MultiLogWriter` fan-outs.
- If no `CheckpointableStreamingLogWriter` found: graceful fallback to normal close (writes `^END^` footer).
- Suspend-resume across this fallback boundary doesn't perfectly preserve log continuity, but doesn't crash. v1 acceptable.
- If they push: opt-in story for existing log writer plugins is the cleanest experience.

## ACL

**"Who can pause/resume/confirm?"** 🟢
- New ACL actions on Execution: `pause`, `resume`, `confirm`.
- Standard Rundeck ACL framework — no new contexts.
- `confirm` action additionally filtered by `requiredConfirmerRoles` configured per suspend request.
- Audit row written for every successful action.

**"Privilege escalation paths?"** 🟢
- Plugins run within their existing security context. Suspend/resume doesn't change permissions.
- Resume context rebuilt from checkpoint plus current ACL — if user/role lost permissions since suspend, resume re-checks.
- No new escalation path introduced.

## Operational concerns

**"Resume called twice — what happens?"** 🟢
- Idempotent at DB level — atomic claim, status transitions, `resume_ready` flag. Second call no-ops.
- Audit table has unique constraint preventing duplicate confirmations.

**"Orphan suspended executions (long-term cleanup)?"** 🟡
- `SuspendRequest` can include a timeout. Timeout fires → execution resumes with timeout payload.
- Indefinite suspends (timeout=null): operator can manually resume with cancel payload, or kill execution.
- **Not handled today:** systematic DB cleanup of long-stuck executions. Future enhancement.

## Design rationale

**"Why not just split into two jobs and use notifications/webhooks?"** 🟢
- Current workaround. Drawbacks:
  - State lost between jobs (data context, output context, schedule context)
  - Audit trail fragmented across executions
  - Workflow visualization shows two unrelated runs
  - Notification delivery isn't reliable; polling adds latency
  - Doesn't compose with retry, error handling, observability
- This proposal preserves single-execution semantics across the pause.

**"Why a new SPI category vs a marker interface on `StepPlugin`?"** 🟢
- Open question — flagged in brief.
- Today's prototype IS a `StepPlugin`.
- Argument for separate SPI: lifecycle hooks specific to interaction (prepare → wait → response); discoverability (plugin browser as a category); standardized config UI for primitive-specific fields.
- Counter: marker interface is lighter ceremony.
- Open to architect input.

**"Why 7 primitives — why not just confirm?"** 🟢
- The SPI shape needs to accommodate the family at introduction so future primitives don't require breaking changes.
- Only `confirm` is shipped at v1; others follow incrementally.
- Family scope from the rx HIL primitive set (Appendix A reference).
- Plugin authors design once against a stable contract.

**"Why `InteractionPlugin` (not `ConfirmationPlugin` or `HumanInputPlugin`)?"** 🟡
- Original PM-suggested name was `ConfirmationPlugin`. Refined when scope expanded to 7 primitives — only one of which is "confirmation."
- `HumanInputPlugin` / `InteractionPlugin` / `HILPlugin` all on the table.
- Picked `InteractionPlugin`: matches Rundeck's noun-based naming (`StepPlugin`, `NotificationPlugin`); broad enough for full primitive family.
- Open to alternatives.

**"Why is operator pause in core (not a plugin)?"** 🟢
- Operator pause/resume is a core operational control — every operator should have it without installing plugins.
- Same engine primitives as the plugin path, but operator-initiated.
- Shipping as core means consistent UI integration in `show.gsp` and immediate availability.

**"How does the dialog UI work? Where do plugins deliver it?"** 🟡
- Today: UIPlugin pattern. The confirm plugin ships CSS + JS as UIPlugin assets bundled with the plugin jar.
- The JS polls `/confirm/status`, injects panel DOM into `#execution-show-content` (with fallbacks to `.execution-show` and `#section-content`).
- Mutual exclusion across multiple confirm-UI plugins via `data-rundeck-confirm-ui` data attribute marker — first-to-render wins.
- Honest framing: this is an implicit convention, not a formal contract.
- Alternatives on the table: a designated container element in `show.gsp` (`<div id="rundeck-hil-panel-container">`), or a Vue `<ui-socket section="execution-hil">` binding that matches Rundeck's modern plugin-UI direction.
- Open to architect input — UIPlugin pattern sufficient, or worth introducing a first-class HiL UI carve-out as part of the SPI surface?

**"How would plugin authors register against a Vue ui-socket?"** 🔴
- *"I haven't designed the Vue side in detail — wanted to surface the question before committing. The current prototype uses the legacy DOM-injection path because that's what UIPlugin supports today. If you'd prefer a Vue-native approach, I'd want to align with whoever owns the Vue plugin pattern in the existing codebase."*

**"What's rx?"** 🟡 *(likely if Appendix A is read)*
- A separate project of mine. Provides a "governed human-in-the-loop" primitive family for ops automation.
- The Rundeck-side `InteractionPlugin` SPI is designed to fulfill rx's HIL primitive family on Rundeck's behalf as one of the first-wave providers.
- Not a dependency: Rundeck can ship the SPI independently. rx is the inspiration for the primitive family scope.

## Tests

**"What's the test coverage?"** 🟡
- Unit tests on the core SPI types and engine wiring.
- Integration tests for the suspend/resume cycle (approve + deny paths).
- E2E tested via REST API — full happy path verified.
- **Honest gap:** multi-node integration tests are limited to manual verification. Could be expanded.
- See `docs/specs/test-plan-workflow-suspend-resume.md` for methodology.

## PR & process

**"How would you split this for review?"** 🟢
- Rough plan: 6–7 thematic PRs. SPI types first (no callers); then engine wiring; then log writer; then persistence + migration; then resume worker; then operator UI; finally confirm plugin.
- Open to alternative splits.
- Estimate: weeks of part-time work to prep.

**"What's the OSS vs commercial story?"** 🟢 *(deflection)*
- *"PM and I have been discussing this separately. Happy to take that conversation offline."*
- Don't get drawn in.

---

## Questions to defer honestly (don't bluff)

- **Specific perf numbers** → *"Haven't benchmarked. Happy to set that up."*
- **Specific edge cases I haven't traced** → *"Good question, let me trace through and get back to you."*
- **"Have you considered [alternative architecture]?"** → *"Tell me more — I may have considered it but want to make sure I understand what you have in mind."*
- **JVM/memory profiling questions** → *"I'd want to profile before answering."*
- **Production-deployment-shape questions** → *"Would defer to your operations team for what makes sense in PD's deployment context."*

## What NOT to say

- ❌ *"This is production-ready."* — it's not.
- ❌ *"All edge cases are covered."* — they're not.
- ❌ *"Upstream will love it."* — you don't know.
- ❌ *"PD's commercial team should..."* — not your call.
- ❌ *"I built this in two weeks"* — even if true, it sounds like under-investment.
we s