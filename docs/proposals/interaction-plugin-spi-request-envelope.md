# InteractionPlugin SPI — implementation notes and one open question

**Status:** Implemented; one decision left for the Rundeck team
**Author:** Alex Honor
**For:** Luis Toledo, Rundeck team
**Amends:** `docs/proposals/interaction-plugin-spi.md` §5.1, §5.5, §5.7, §5.8, §5.9
**Branch:** `pr/upstream-submission` — one commit on `3bb960df0f`

---

## What this note is

The proposal specifies the SPI. This note records the two design decisions taken while
turning it into working code, the evidence behind each, and the one question that is
genuinely yours rather than mine.

The branch is 73 files and 7,029 insertions: the engine suspend/resume primitive, the
`InteractionPlugin` contract, and the confirm step as its first implementation. It
modifies no existing file that upstream has changed since, and the core test suite
passes. It is the whole feature, not a slice — the design is easier to judge with a
consumer attached.

## Decision 1 — the request envelope composes `SuspendRequest`

The proposal originally had `HILRequest extends SuspendRequest`. Porting it onto
current `main` showed that subclassing does not survive the persistence path. Three
problems, lightest first.

**`SuspendRequest` is final.** `SuspendRequest.java:53` declares
`public final class SuspendRequest`. Extending it means unsealing an engine type that
non-HIL consumers share. It also inherits a trap: the parent's `equals`/`hashCode`
cover its own six fields, so subclasses adding fields get value semantics that ignore
them — two `ConfirmRequest`s differing only in `decisionSet` would compare equal.

**The static `builder()` collides.** A hard compile error:

```
error: builder() in ConfirmRequest cannot hide builder() in SuspendRequest
  return type ConfirmRequest.Builder is not compatible with SuspendRequest.Builder
```

Every concrete primitive would need a differently-named builder.

**Typed fields would not survive a resume.** This is the one that settled it.
`ResumePayload` carries `@JsonTypeInfo` and `@JsonSubTypes`; `SuspendRequest` carries
neither. But it is persisted and read back:

- `ExecutionCheckpoint.java:62` — `private final SuspendRequest suspendRequest;`
- `ExecutionResumeService.groovy:211` — `objectMapper.readValue(execution.checkpointData, ExecutionCheckpoint)`

A `ConfirmRequest` written into a checkpoint would return as a plain `SuspendRequest`,
dropping `message`, `criticality`, `source`, `decisionSet` and
`requiredConfirmerRoles`. And `validateResponse()` — declared on `HILRequest`, reading
`decisionSet` — would be unreachable after resume, which §7's validation rules depend
on.

The proposal's §3 goal, "reuse existing engine surfaces, introduce no parallel
polymorphism machinery," is right. But only the *response* half of that machinery
exists. Subclassing assumed a request half that is not there.

**So `HILRequest` produces a `SuspendRequest` instead of being one,** and roots its own
Jackson polymorphism on a `"primitive"` discriminator, registered through the existing
`JacksonSubtypeRegistrar`. `SuspendRequest` is untouched — byte-identical to what the
engine change introduces. All three problems disappear. The cost is one unwrap call,
`request.toSuspendRequest()`, at a boundary the engine already owns.

**The alternative, for the record:** add `@JsonTypeInfo` to `SuspendRequest` and keep
inheritance. It preserves the original hierarchy, but changes the serialized form of a
type operator-pause and future suspendable plugins also use, and leaves the first two
problems as permanent papercuts for every primitive author. If you would rather keep
the hierarchy at that price, the change is contained.

## Decision 2 — no new plugin service

The proposal had interaction plugins register under a new `"Interaction"` service.
The implementation does not. They register under the existing `WorkflowStep` service
and implement both `StepPlugin` and `InteractionPlugin`; `StepPluginAdapter` prefers
the typed contract when a plugin offers it.

The reason is that a workflow step's plugin service is not stored in the job
definition — it is derived. `WorkflowController` resolves
`isNodeStep ? WorkflowNodeStep : WorkflowStep`, and `PluginStep` carries only
`Boolean nodeStep` and `String type`. There is no third state.

So `"Interaction"` as a step-hosting service means changing how a job records what a
step is: a job definition format change, cascading into the `PluginStep` domain and a
migration, job XML/YAML import/export, every `isNodeStep ?` branch,
`ScheduledExecutionService` validation, and SCM export/import — job definitions being
what SCM versions. An older Rundeck would not read a job containing an interaction
step.

That is a backward-compatibility surface, and it is separable from the contract. This
branch gets the typed SPI, its persistence behavior and its engine validation without
any of it.

**What that defers:** interaction steps have no dedicated job-edit palette category,
so they appear among ordinary workflow steps; and recognition is by `instanceof` at
dispatch rather than by registration, so nothing prevents a class implementing the
interface without the engine knowing. Both are real, neither is a contract problem.

## What the implementation added beyond the proposal

| Addition | Why |
|---|---|
| `HILRequest.METADATA_KEY` (`"hilRequest"`) and `fromSuspendMetadata(Map)` | The typed request has to reach the resume path, but `StepExecutionContext` exposes only `getResumePayload()` and `getSuspendMetadata()`. `toSuspendRequest()` serializes the request into envelope metadata; the resume path resolves it back through the discriminator. No schema change, no new accessor. |
| `timeoutAction` on `ConfirmRequest` | It must be frozen at suspend time. The typed request is a better home for it than the untyped metadata map. |
| `@JsonIgnore` on `getPrimitive()` | Without it Jackson emits a `primitive` property the creator does not accept, and every response fails to deserialize. Found by the round-trip tests. Same fix `ResumePayload.getType()` already carries. |
| `ConfirmResponse` supersedes `ConfirmationPayload` | Two `ResumePayload` subtypes cannot share the `"confirmation"` discriminator. Field sets were identical, so the change was mechanical. |

The metadata carrier is the one mechanism I invented rather than ported, so it is the
part most worth your scrutiny. The alternative is exposing the suspend payload on
`StepExecutionContext` and storing the request there — cleaner conceptually, but it
widens an engine interface for a case only this SPI has so far.

## What I'd like from you

1. **Is `"Interaction"` worth a job definition format change?** This is the real
   question. The branch works without it; what it costs is the palette category and
   registry-level enforcement. You are far better placed than I am to weigh that
   against the compatibility surface, and against the fact that
   `ServiceNameConstants` has not gained an entry since `PluginGroup` in March 2022.
2. **Does the composed envelope hold up,** or would you rather keep the original
   hierarchy and annotate `SuspendRequest`?
3. **Is the metadata carrier the right mechanism,** or should `StepExecutionContext`
   expose the suspend payload directly?

I'll rework the branch to whatever you land on.
