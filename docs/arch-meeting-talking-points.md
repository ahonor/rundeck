# Talking points — Rundeck architecture meeting

**Personal cheat sheet — not shared.** Time-budgeted script. Glance at during the meeting; don't read aloud.

**Total budget:** 15–20 min. ~13 min content + 2–5 min Q&A.

---

## (1) Open — 1 min

- One-sentence framing: *"I built suspend/resume + a new plugin SPI on a Rundeck fork. Want to walk through the design and hear your concerns."*
- Brief should be on their screen; we'll work through it together.

**Don't:**
- Lead with founder/history credentials. They know.
- Apologize for the size of the work.

## (2) Problem & proposal — 1 min

- Today: workflows can't pause for human input. Workarounds (split jobs + notifications) are fragile, lose state, fragment audit.
- Proposal: a new first-class plugin SPI category — **`InteractionPlugin`** — for steps that solicit structured input from a human mid-execution.
- Plus supporting engine primitives: suspend/resume SPI, log writer checkpoint, schema for state.

## (3) Architecture overview — 2 min

- 3 layers: plugin → app → core engine + core SPI.
- Plugin authors implement two methods (`prepareRequest`, `onResponse`) plus declare which primitives they support. Engine handles everything else.
- Two consumers of the engine primitives today:
  1. The confirm plugin — uses `InteractionPlugin` SPI.
  2. Operator pause/resume in core — same primitives, no plugin needed.
- The SPI is designed for **7 primitives** (`confirm`, `choose`, `ask`, `review`, `attest`, `escalate`, `rank`). `confirm` is the only one shipped.

## (4) Demo 1 — operator pause/resume — 3–4 min

**Pre-flight (open before the meeting):**
- [ ] Rundeck running and reachable
- [ ] Multi-step job loaded and ready to run
- [ ] Show page bookmarked / tab open

**Flow:**
- Run multi-step workflow.
- Mid-execution, click pause button.
- Show execution state → "waiting" with pause icon on activity list.
- Click resume → workflow continues.
- Highlight: log continuity preserved (no duplicate header, no missing entries).

**Soundbite:** *"Same engine primitives as the confirm plugin — operator-initiated rather than plugin-initiated."*

## (5) Demo 2 — confirm plugin — 3–4 min

**Pre-flight:**
- [ ] Job with confirm step in middle ready
- [ ] Confirmation submission UI accessible

**Flow:**
- Run job → workflow pauses at confirm step.
- Confirmation panel slides in with message, decision options.
- Approve → workflow continues.
- (If time) Show a denied execution from history.

**Soundbite:** *"The plugin author wrote no suspend/resume code. They implemented `prepareRequest` and `onResponse`. Everything else is engine."*

## (6) The new SPI — 2 min

- Walk `InteractionPlugin` interface (from brief). Three methods: `supportedPrimitives`, `prepareRequest`, `onResponse`.
- Note: family scope comes from rx HIL primitive set; `confirm` is the v1 implementation, others are designed-for-not-built.
- Naming: settled on `InteractionPlugin` because primitive family is broader than confirmation.

**Pause for naming pushback if any.** Architects may have a different view; follow them if so.

## (7) Open questions — 2 min

Don't try to answer. Surface and invite reactions. Top 3–4 from the brief:

1. **SPI typing — A or B?** Unified envelope vs per-primitive subtypes. (You lean B.)
2. **`@Experimental` cycle.** How long before promotion to stable, given 6 of 7 primitives unbuilt?
3. **Schema migration concerns.** Multi-DB compat (H2/MySQL/Postgres) — column placement, rollback strategy?
4. **Multi-node hardening.** Heartbeat for stuck claims — needed for v1 or later?
5. **Plugin UI delivery.** Today: UIPlugin + `data-rundeck-confirm-ui` marker. On the table: formalized GSP carve-out or Vue ui-socket binding. Sufficient or first-class contract needed?

If they engage on a different open question from the brief, follow them.

## (8) Wrap — 30 sec

- Branch URL: `github.com/ahonor/rundeck` → `cycle/workflow-suspend-resume`.
- Brief + dev-cycle artifacts in `docs/`.
- Happy to walk any layer offline.
- Closing question: *"What's the next conversation that should happen?"*

---

## Tone notes (re-read before walking in)

- **No founder card.** They know who you are. Stating it would feel like rank-pulling.
- **No commercial/OSS debate.** *"PM and I have been discussing — happy to summarize separately."* Keep moving.
- **"I don't know, let me get back to you with specifics" is acceptable.** Bluffing isn't.
- **Don't apologize for size.** ~10k LOC is what the feature needed. Matter-of-fact.
- **Match register.** Senior engineers; talk to them as peers, not as a vendor pitching.

## Pivots if running long

- **Cut one demo to ~2 min** if Q&A interest is high early. Keep the confirm one (more novel).
- **Skip §7 (open questions recap)** — they have the brief; they can raise their own.
- **Hard stop §8** — closing email captures it.

## Pivots if running short

- More demo depth — show resume across Rundeck restart if you've got the setup.
- Walk schema migration columns with per-column rationale.
- Open `JacksonSubtypeRegistrar` source and walk how a third party would register a new primitive payload.
