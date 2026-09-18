# InteractionPlugin SPI — the request envelope composes the engine type

**Status:** Decided, implemented, open to pushback
**Author:** Alex Honor
**For:** Luis Toledo, Rundeck team
**Amends:** `docs/proposals/interaction-plugin-spi.md` §5.1, §5.4, §5.5, §5.7
**Branch:** `pr/interaction-plugin-spi` (based on `3bb960df0f`)

---

## Summary

The proposal specified `HILRequest extends SuspendRequest`. Porting it onto current
`main` showed that subclassing doesn't survive contact with the persistence path, so
the SPI now **composes** the engine type instead: `HILRequest` produces a
`SuspendRequest` via `toSuspendRequest()`.

The result is a PR that modifies **no existing file**:

- 13 new files, 1,140 insertions, 0 modifications
- `:core:compileJava` → `BUILD SUCCESSFUL`
- `HILRequestRoundTripSpec` → 4 tests, 4 passing

Staleness isn't a factor. The original work branched from `280ecc5702` (9 April 2026)
and is ~1,250 commits behind, but none of that churn touches this surface.

## Why subclassing didn't work

Three problems, lightest first.

### 1. `SuspendRequest` is final

`SuspendRequest.java:53` declares `public final class SuspendRequest`. Extending it
means unsealing it — modifying an engine type that non-HIL consumers already share.

It also inherits a trap: `SuspendRequest` is an immutable value type whose `equals`,
`hashCode` and `toString` cover its own six fields. Subclasses that add fields get
value semantics that silently ignore them — two `ConfirmRequest` instances differing
only in `decisionSet` would compare equal.

### 2. The static `builder()` collides

A hard compile error:

```
error: builder() in ConfirmRequest cannot hide builder() in SuspendRequest
  return type ConfirmRequest.Builder is not compatible with SuspendRequest.Builder
```

`SuspendRequest` exposes `static Builder builder()`, so every concrete primitive
subtype would have to pick a different name.

### 3. Typed fields don't survive a resume

This is the one that settled it.

`ResumePayload` carries the polymorphism machinery — `@JsonTypeInfo` plus
`@JsonSubTypes`. `SuspendRequest` carries none of it. But it is persisted and read
back:

- `ExecutionCheckpoint.java:62` — `private final SuspendRequest suspendRequest;`
- `ExecutionResumeService.groovy:211` — `objectMapper.readValue(execution.checkpointData, ExecutionCheckpoint)`

So a `ConfirmRequest` written into a checkpoint would come back as a plain
`SuspendRequest`, dropping `message`, `criticality`, `source`, `decisionSet` and
`requiredConfirmerRoles`.

The follow-on: `validateResponse()` is declared on `HILRequest` and reads
`decisionSet`. After a resume the engine would hold a `SuspendRequest`, not a
`HILRequest`, so the method would be unreachable — and §7's engine validation rules
depend on it being callable on every response before dispatch.

The proposal's §3 goal — "reuse existing engine surfaces, do not introduce parallel
polymorphism machinery" — is right. But only the *response* half of that machinery
exists today. Subclassing assumed a request half that isn't there.

## What the SPI does instead

`HILRequest` is the polymorphism root for the request family, mirroring how
`ResumePayload` roots the response family:

```java
@Experimental
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "primitive")
@JsonSubTypes({ @JsonSubTypes.Type(value = ConfirmRequest.class, name = HILPrimitives.CONFIRM) })
public abstract class HILRequest implements Serializable {

    @JsonIgnore
    public abstract String getPrimitive();

    public final SuspendRequest toSuspendRequest() { ... }

    protected Map<String, Object> suspendMetadata() { return Collections.emptyMap(); }

    public List<String> validateResponse(HILResponse response) { return Collections.emptyList(); }
}
```

Four consequences worth naming:

- **No engine type changes.** `SuspendRequest` stays final and byte-identical to what
  the engine PR introduces. The unsealing, the value-semantics trap and the builder
  collision all disappear.
- **Typed fields survive persistence,** because the discriminator lives on a type we
  fully own. `HILRequestRoundTripSpec` asserts this directly.
- **No parallel machinery.** Third-party primitives register subtypes through the
  existing `JacksonSubtypeRegistrar`, the same hook `ResumePayload` subtypes use.
- **`suspendMetadata()` keeps the existing consumers working.** REST controllers and
  the paused-step UI read `SuspendRequest.getMetadata()` today; concrete requests
  project their fields into it. The typed object stays the source of truth, the map
  is a view of it.

The cost is one unwrap at the suspend boundary — the engine calls
`hilRequest.toSuspendRequest()` rather than passing the object straight through. That
lands in the engine PR, which is touching dispatch anyway.

**The alternative we rejected:** mirror `@JsonTypeInfo` onto `SuspendRequest` and keep
inheritance. It preserves the proposal's hierarchy and leaves
`StepExecutionContext.suspend()` untouched, but it changes the serialized form of a
type operator-pause and future suspendable plugins also use — a persistence-format
change — and it leaves problems 1 and 2 as permanent papercuts for every primitive
author. If you'd rather keep `suspend()` signature-stable at that price, say so and
I'll switch it back; the change is contained.

## Smaller items, resolved

| Item | Finding | Resolution |
|---|---|---|
| Discriminator collision | `ConfirmResponse` uses `@JsonTypeName("confirmation")`, already claimed by `ConfirmationPayload` in `ResumePayload`'s `@JsonSubTypes` | `ConfirmResponse` replaces `ConfirmationPayload`; stated explicitly in the proposal |
| `HILResponse` shape | §5.2 calls it an abstract class, §5.6 declares an interface | Interface — it extends `ResumePayload`, which is an interface |
| `@Experimental` | As written it is self-annotated and has no `@Retention`/`@Target` | Written with `@Documented`, `@Retention(CLASS)`, `@Target({TYPE, METHOD, FIELD, CONSTRUCTOR})` |

## PR split

The compiler drew the seam. `SuspendedStepResult` can't go in an SPI-only PR — it
implements `isSuspended()` and `getSuspendRequest()`, which are default methods added
to the `StepExecutionResult` interface. That's a modification to an existing engine
type, so it belongs with the engine change.

1. **PR 1 — SPI contract.** `plugins/interaction/*` plus the `suspend` value types
   (`SuspendRequest`, `ResumePayload`, `JacksonSubtypeRegistrar`). All new files,
   compiles standalone, no behavior change. This is what's on the branch now.
2. **PR 2 — engine suspend/resume.** `StepExecutionResult` defaults, checkpointing,
   engine loop, lifecycle and schema.
3. **PR 3 — confirm implementation.** The plugin, REST surface, ACL, UI.

One open question on PR 1's boundary: `ResumePayload`'s `@JsonSubTypes`
hard-references `ConfirmationPayload` and `OperatorResumePayload`, which pulls both
into PR 1 even though operator-pause is a PR 3 concern. Leaving `@JsonSubTypes` empty
and relying on `JacksonSubtypeRegistrar` would keep PR 1 to the contract alone. Happy
to go either way.

## What I'd like from you

1. Does the composed shape work for you, or would you rather keep
   `StepExecutionContext.suspend()` signature-stable and pay the inheritance costs?
2. Does the three-PR split match how you'd want to review this?
3. Does the `@JsonSubTypes` question change PR 1's file set?

I'll rework the branch to whatever you land on before opening anything.
