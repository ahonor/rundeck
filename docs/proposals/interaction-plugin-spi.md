# InteractionPlugin SPI

**Status:** Proposal — reference implementation built and passing
**Target version:** Rundeck 6.x (`@Experimental`)
**Author:** Alex Honor
**Branch:** `cycle/workflow-suspend-resume`
**Related:** `docs/confirm-pause-resume-spec.md` (as-built), `docs/specs/workflow-suspend-resume.md` (engine primitive), `docs/proposals/interaction-plugin-spi-request-envelope.md` (why the request side composes)
**Revised:** 2026-09-18 — `HILRequest` composes `SuspendRequest` instead of extending it (§5.1, §5.5), and interaction plugins register under the existing `WorkflowStep` service rather than a new service category (§5.8, §5.9). Reference implementation on branch `pr/workflow-suspend-resume`: 73 files, engine + SPI + confirm implementation, building green against upstream `3bb960df0f`.

---

## 1. Summary

Add a new first-class plugin SPI category, `InteractionPlugin`, for workflow steps that solicit structured input from a human mid-execution. Sits alongside `StepPlugin`, `NotificationPlugin`, `LogFilterPlugin`, etc.

The SPI shape supports a family of human-input primitives (confirm, choose, ask, review, attest, escalate, rank, and any third-party-defined primitive). v1 ships only the `confirm` primitive concretely. The primitive family is identified by namespaced string, not a closed enum, so new primitives are added by plugins without core changes.

This document specifies the plugin-author-facing SPI contract. The engine plumbing (suspend/resume, log checkpoint, REST, persistence) is described in the as-built spec referenced above and is not part of this contract.

## 2. Motivation

Today Rundeck workflows can't pause for typed human input as a first-class operation. Job Options solicit input at job start but cannot run mid-workflow, carry rich context, or produce signed evidence. The split-job-plus-notification workaround loses data context, fragments audit, and doesn't compose with workflow-level retry or observability.

A first-class human-input SPI generalizes mid-workflow interaction and gives Rundeck users a coherent paused-workflow experience.

## 3. Goals

- Define a typed, family-aware plugin SPI for mid-workflow human input.
- Ship `confirm` as the v1 concrete primitive; publish well-known names for the broader family as constants.
- Keep the primitive family open. Third parties define new primitives without recompiling core.
- Reuse existing engine surfaces: `SuspendRequest`, `ResumePayload`, `JacksonSubtypeRegistrar`. Introduce no new polymorphism *mechanism* — the request family adds a second Jackson root (`HILRequest`), configured exactly as `ResumePayload` already is and registered through the same `JacksonSubtypeRegistrar` hook. Leave the engine's existing value types unmodified: `SuspendRequest` is neither subclassed, unsealed, nor re-annotated.
- Require no new plugin service and no job definition format change. Interaction plugins register under the existing `WorkflowStep` service (§5.8).
- Annotate every new public type `@Experimental`. Reserve the right to revise shape before stable promotion.

## 4. Non-goals

- Implementing primitives other than `confirm`. The other six well-known primitives are designed-for, not built. Concrete request/response subtypes ship with their respective implementations later, in core or in third-party plugins.
- Changing the engine suspend/resume contract. The new SPI is a typed layer over `SuspendRequest`/`ResumePayload`, not a replacement.
- Specifying UI, REST endpoints, or persistence. Those are covered in the as-built spec and are not part of this SPI's plugin-author-facing contract.
- Specifying behavior for non-HIL suspendable consumers (e.g., operator-pause). Those use `SuspendRequest`/`ResumePayload` directly. See §5.11 for how the two relate.
- Gating which primitives can be implemented. Core does not maintain a list of permitted primitives.
- Introducing an `"Interaction"` plugin service, a dedicated job-edit palette category, or registry-level enforcement of the contract. Those depend on a job definition format change and are proposed separately; §5.8 sets out why and what is deferred with them.

## 5. Design

### 5.1 Type hierarchy

```
HILRequest ──produces──▶ SuspendRequest      ResumePayload
    └── ConfirmRequest                           └── HILResponse
    (third-party request types)                        └── ConfirmResponse
                                                       (third-party response types)
```

Two families, attached to the engine differently.

The **response** side is rooted at the engine's existing `ResumePayload`. `HILResponse` is an abstract envelope that adds a primitive identifier (a namespaced string) to it, and per-primitive concrete types extend that envelope. `ResumePayload` already carries `@JsonTypeInfo`/`@JsonSubTypes`, so a HIL response is a sub-family of an existing polymorphic hierarchy.

The **request** side *composes* `SuspendRequest` rather than extending it. `HILRequest` is its own polymorphic root and produces an engine envelope via `toSuspendRequest()`.

The asymmetry is deliberate, and it is forced by the engine as it exists. `SuspendRequest` is `final`, carries no `@JsonTypeInfo`, and is round-tripped through Jackson as part of `ExecutionCheckpoint` on the resume path. A subclass of it would deserialize back as a plain `SuspendRequest`, dropping every per-primitive field and making `validateResponse()` unreachable after a resume — which §7's validation rules depend on. Composition keeps the engine type unmodified and puts the discriminator on a type the SPI owns. See `interaction-plugin-spi-request-envelope.md` for the full rationale and the rejected alternative.

Each primitive gets its own concrete request and response type rather than a single unified envelope with a polymorphic payload. The seven well-known primitives have genuinely different shapes — `attest` carries countersign candidates, `ask` carries a validation schema, `rank` carries an ordered list — and pushing those shapes into a `Map<String, Object>` payload moves schema enforcement out of the type system into runtime checks. The cost of per-primitive subtypes is paid once when the type is added; the cost of a unified envelope is paid by every plugin author every time. A unified envelope can be reached later by collapsing subtypes; the reverse — decomposing a unified envelope into per-primitive subtypes — would be a breaking change for any plugin already shipped.

Non-HIL suspendable consumers (operator-pause via `OperatorResumePayload`, future plugins) remain at the `SuspendRequest`/`ResumePayload` level and are unaffected. §5.11 sets out that relationship, since it is the first thing most readers ask about.

### 5.2 Package layout

```
core/src/main/java/com/dtolabs/rundeck/plugins/interaction/
    InteractionPlugin.java          // SPI interface
    HILPrimitives.java              // well-known primitive name constants
    HILRequest.java                 // abstract envelope (produces a SuspendRequest)
    HILResponse.java                // abstract envelope (interface, extends ResumePayload)
    ConfirmRequest.java             // concrete v1 request
    ConfirmResponse.java            // concrete v1 response
    Experimental.java               // @Experimental annotation (§5.10)
```

Sibling to `plugins/step`, `plugins/notification`, `plugins/workflow`, etc.

### 5.3 Primitive identifier

Primitives are identified by string. Convention: `vendor.primitive` namespacing to reduce collisions when independent third parties define their own primitives.

```java
@Experimental
public final class HILPrimitives {

    /** Approve/deny gate. Concrete types ship in core at v1. */
    public static final String CONFIRM  = "rundeck.confirm";

    /** Pick one of N options. Well-known name; concrete types not shipped at v1. */
    public static final String CHOOSE   = "rundeck.choose";

    /** Typed structured input (e.g., string with regex validation). */
    public static final String ASK      = "rundeck.ask";

    /** Proceed/modify/abort against a proposed plan. */
    public static final String REVIEW   = "rundeck.review";

    /** Signed witness, optionally counter-signed. */
    public static final String ATTEST   = "rundeck.attest";

    /** Policy-override request to a higher approver pool. */
    public static final String ESCALATE = "rundeck.escalate";

    /** Order items by priority. */
    public static final String RANK     = "rundeck.rank";

    private HILPrimitives() {}
}
```

The constants are convenience, not enforcement. A plugin defining its own primitive (e.g., `"acme.signature"`) is a first-class participant; core does not distinguish well-known names from third-party names.

Strings rather than an enum: a closed enum would force every new primitive through a Rundeck core release, making core a chokepoint for what is by design a plugin-extension surface. Strings let third parties introduce new primitives by shipping a plugin jar, no core change required. The convention also matches `ResumePayload`'s existing string discriminators (`"confirmation"`, `"operator-resume"`).

### 5.4 `InteractionPlugin` interface

```java
@Experimental
public interface InteractionPlugin {

    /**
     * Declare the primitive name this plugin handles. Plugins handle exactly
     * one primitive. Use cases that bundle multiple primitives ship multiple
     * plugin classes — typically in the same jar — sharing a helper.
     * Plugins may declare any non-empty namespaced string; core does not gate
     * the set of names.
     */
    String supportedPrimitive();

    /**
     * Called when the workflow reaches this step. Returns a typed request
     * envelope; the plugin does NOT call {@code suspend()} itself — the engine
     * passes the returned envelope to {@code StepExecutionContext.suspend()}
     * after this method returns.
     *
     * The returned request's primitive must equal {@link #supportedPrimitive()}.
     */
    HILRequest prepareRequest(
            PluginStepContext context,
            Map<String, Object> configuration);

    /**
     * Called when a structured response arrives via the resume path. The
     * response's primitive matches the request's primitive. Plugins update
     * output context, log details, or throw on negative outcomes (denied,
     * timeout, error).
     */
    void onResponse(
            PluginStepContext context,
            HILResponse response) throws StepException;
}
```

Three methods, all at the SPI layer. No engine concerns leak to plugin authors: persistence, REST, ACL, audit, and the resume worker are core responsibilities.

`prepareRequest` returns and `onResponse` accepts the abstract envelope types. Plugin authors cast to their concrete subtype as needed.

Each plugin handles exactly one primitive. Most Rundeck plugins are single-purpose, and the case for a single class declaring support for multiple primitives is well-served by shipping multiple plugin classes — typically in the same jar — that share a helper. That packaging pattern is already in use elsewhere. Single-primitive also keeps the SPI surface and the engine routing rules simple; relaxing the contract to multi-primitive later is non-breaking, the reverse is not.

Negative outcomes — denied, timed out, plugin error — propagate as `StepException`. This matches the existing `StepPlugin` and `NodeStepPlugin` convention: a denied confirm is genuinely a step failure (the workflow does not proceed past it), and throwing is the natural shape for that signal. The data the plugin acts on — decision string, comment, timestamp, confirmer identity — is already on the `HILResponse`; the exception carries the workflow signal, the response carries the audit data.

`onResponse` is responsible for surfacing the response into the rest of the workflow. Two side-effects are typically expected:

- **Log entries.** A structured summary of the outcome (decision, actor, comment, timestamp) written via `context.getLogger().info(...)` or `context.getExecutionContext().getExecutionListener().log(...)` — the same mechanisms `StepPlugin` implementations use.
- **Output context.** Selected response fields published to the workflow's output data context so downstream steps can reference them as context variables, via `context.getOutputContext().addOutput(<key>, <value>)`. The plugin writes flat keys; the engine handles step-level namespacing so downstream references look like `${data.<step-id>.<key>}` per existing Rundeck step-output mechanics. Well-known primitives publish a fixed set of keys (see the per-primitive spec entries — for `confirm`, §5.7); third-party primitives document their own keys.

The engine writes a baseline audit record automatically (primitive name, request id, response timestamp, responder identity). Plugin-emitted log and output are in addition to that, not instead of it.

When a suspension reaches its `timeoutMs` without a response, the engine generates a synthetic `HILResponse` with the timeout flag set (e.g., `ConfirmResponse.isTimeout() == true`) and calls `onResponse` with that response. There is no separate `onTimeout` callback; the plugin handles the timeout case in the same method. Plugins typically throw `StepException` on timeout to fail the step, or treat it as a configured alternate decision (e.g., a "deny on timeout" policy).

The `PluginStepContext` passed to `onResponse` reflects the configuration captured at suspend time — the freeze contract. The engine snapshots the resolved configuration when `prepareRequest` returns and presents that snapshot back to `onResponse`. Plugin authors do not re-read job-definition state on resume; the frozen context is the authoritative view of what this step was asked to do.

### 5.5 `HILRequest` (abstract)

```java
@Experimental
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "primitive")
@JsonSubTypes({
        @JsonSubTypes.Type(value = ConfirmRequest.class, name = HILPrimitives.CONFIRM)
})
public abstract class HILRequest implements Serializable {

    /**
     * Primitive name. Conventionally namespaced ("vendor.primitive"). Never null or empty.
     *
     * {@code @JsonIgnore} prevents double-emission: {@code @JsonTypeInfo} already
     * writes this as the {@code "primitive"} property.
     */
    @JsonIgnore
    public abstract String getPrimitive();

    /** Engine-facing envelope, handed to {@code StepExecutionContext.suspend(...)}. */
    public final SuspendRequest toSuspendRequest() { /* projects the fields below */ }

    /**
     * Fields to publish into {@code SuspendRequest.getMetadata()} for
     * framework-visible consumers — REST controllers, the paused-step UI.
     * The typed object stays the source of truth; this is a view of it.
     * Default publishes nothing.
     */
    protected Map<String, Object> suspendMetadata() {
        return Collections.emptyMap();
    }

    /**
     * Validate that a response is well-formed for this specific request.
     * Called by the engine after deserialization, before {@code onResponse}.
     * A non-empty return causes the engine to reject the response with
     * HTTP 400 and surface the messages to the caller; {@code onResponse}
     * is not invoked. Default implementation returns no errors.
     */
    public List<String> validateResponse(HILResponse response) {
        return Collections.emptyList();
    }
}
```

Carries the suspend-level fields it needs to build an envelope (`token`, `timeoutMs`, `reason`) and sets `waitingFor` to the primitive name. HIL-specific fields are declared as typed properties on concrete subclasses, not as untyped entries in the `metadata` map. The `Map<String, Object>` shape is the loosest version of the data and gives back the type safety that motivated per-primitive subtypes; primitive-specific data belongs in primitive-specific fields.

The typed request itself is carried to the resume path through the same envelope. `toSuspendRequest()` serializes the concrete request under the reserved metadata key `HILRequest.METADATA_KEY` (`"hilRequest"`), and `HILRequest.fromSuspendMetadata(Map)` resolves it back to its concrete subtype via the `"primitive"` discriminator. This is what makes `validateResponse()` reachable after a resume; it needs no schema change and no new accessor on `StepExecutionContext`, which today exposes only `getResumePayload()` and `getSuspendMetadata()`. Consumers reading suspend metadata for display should ignore the reserved key. A suspension not created by an `InteractionPlugin` returns `null`.

`suspendMetadata()` is how the typed data reaches consumers that read the engine envelope rather than the SPI types — the confirm REST controller and the paused-step UI read `SuspendRequest.getMetadata()` today. Concrete requests project their fields into it. This is a projection for existing readers, not a second source of truth; the engine persists the typed `HILRequest` and resolves it back through the `"primitive"` discriminator on resume.

`validateResponse` is the per-primitive enforcement hook. Each concrete request type owns the rules for what counts as a valid response — for `confirm`, that the decision is in `decisionSet`; for `ask`, that the input matches the validation schema; etc. Engine validation rules in §7 invoke this hook on every response before dispatching. Plugin authors implementing a new primitive override `validateResponse`; primitives that need no per-request validation accept the default empty-list return.

### 5.6 `HILResponse` (interface)

```java
@Experimental
public interface HILResponse extends ResumePayload {

    /** Primitive name. Matches the request's primitive (string equality). */
    @JsonIgnore
    String getPrimitive();
}
```

Inherits `ResumePayload.getType()` and the existing Jackson polymorphism (`@JsonTypeInfo` + `@JsonSubTypes` + `JacksonSubtypeRegistrar`). No new polymorphism machinery is introduced on this side. Concrete HIL response subtypes register through `JacksonSubtypeRegistrar`. Third-party HIL primitives ship their own registrars in their own jars.

`ConfirmResponse` takes the `"confirmation"` discriminator and **replaces** the prototype's `ConfirmationPayload`, rather than coexisting with it — two `ResumePayload` subtypes cannot share a discriminator.

The HIL response family is a sub-family of resume payloads, so it slots into the existing hierarchy rather than living next to it. An alternative — `HILResponse` as a separate envelope wrapping a `ResumePayload` — was rejected because it would duplicate polymorphism the engine already supports, and force the engine to resolve discriminators in two places.

### 5.7 `ConfirmRequest` and `ConfirmResponse`

Concrete v1 types.

```java
@Experimental
public final class ConfirmRequest extends HILRequest {

    private final String message;                       // human-readable prompt
    private final String criticality;                   // "low" | "medium" | "high" | ...
    private final String source;                        // "job" | "api" | ...
    private final String timeoutAction;                 // "deny" | "approve" | "fail"
    private final List<String> decisionSet;             // e.g., ["approve", "deny"]
    private final List<String> requiredConfirmerRoles;  // ACL gate

    @Override
    public String getPrimitive() { return HILPrimitives.CONFIRM; }

    @Override
    public List<String> validateResponse(HILResponse response) {
        if (!(response instanceof ConfirmResponse)) {
            return List.of("response is not a ConfirmResponse");
        }
        ConfirmResponse cr = (ConfirmResponse) response;
        if (cr.isTimeout()) return List.of();   // synthetic timeout — exempt
        if (!decisionSet.contains(cr.getDecision())) {
            return List.of("decision '" + cr.getDecision()
                    + "' is not a member of decisionSet " + decisionSet);
        }
        return List.of();
    }

    // accessors, builder, JsonCreator
}

@Experimental
@JsonTypeName("confirmation")
public final class ConfirmResponse implements HILResponse {

    private final String decision;            // member of request.decisionSet
    private final String confirmedBy;         // user id
    private final List<String> confirmerRoles;
    private final String comment;             // free text, nullable
    private final String confirmedAt;         // ISO-8601
    private final boolean timeout;            // synthetic timeout payload

    @Override public String getPrimitive() { return HILPrimitives.CONFIRM; }
    @Override public String getType() { return "confirmation"; }

    // accessors, JsonCreator
}
```

`ConfirmResponse`'s `ResumePayload` discriminator is `"confirmation"`.

`getPrimitive()` carries `@JsonIgnore` for the same reason `ResumePayload.getType()` does: it is a derived constant, not a stored field. Without it Jackson emits a `primitive` property that the creator does not accept, and deserialization fails with `UnrecognizedPropertyException`. Implementations must annotate the override as well as the interface declaration.

**Required output keys for the `confirm` primitive.** A `rundeck.confirm` implementation publishes the following keys to the output context in `onResponse` so downstream steps can reference them as `${data.<step-id>.<key>}`. The set is required, not advisory: workflows authored against these keys must work regardless of which `rundeck.confirm` plugin is installed.

| Key | Type | Source field |
|---|---|---|
| `decision` | String | `ConfirmResponse.decision` |
| `confirmedBy` | String | `ConfirmResponse.confirmedBy` |
| `confirmerRoles` | List<String> | `ConfirmResponse.confirmerRoles` |
| `comment` | String (may be empty) | `ConfirmResponse.comment` |
| `confirmedAt` | String (ISO-8601) | `ConfirmResponse.confirmedAt` |
| `timeout` | Boolean | `ConfirmResponse.timeout` |

### 5.8 Plugin registration and packaging

An `InteractionPlugin` registers under the **existing `WorkflowStep` service** and also implements `StepPlugin`. It does not introduce a new plugin service category.

```java
@Plugin(name = "confirm", service = ServiceNameConstants.WorkflowStep)
@PluginDescription(
    title = "Confirm",
    description = "Pause workflow execution and wait for human confirmation"
)
public class ConfirmWorkflowStep implements StepPlugin, InteractionPlugin {

    @PluginProperty(title = "Message", required = true)
    String message;

    // ... remaining @PluginProperty fields

    @Override public String supportedPrimitive() { return HILPrimitives.CONFIRM; }

    @Override public HILRequest prepareRequest(
            PluginStepContext context,
            Map<String, Object> configuration) { /* build a ConfirmRequest */ }

    @Override public void onResponse(
            PluginStepContext context,
            HILResponse response) throws StepException { /* handle the ConfirmResponse */ }

    @Override public void executeStep(
            PluginStepContext context,
            Map<String, Object> configuration) throws StepException {
        // Bridge for the StepPlugin contract; see §5.9.
    }
}
```

**Why not a dedicated service name.** A workflow step's plugin service is not stored in the job definition — it is *derived*, from a boolean. `WorkflowController` resolves `isNodeStep ? ServiceNameConstants.WorkflowNodeStep : ServiceNameConstants.WorkflowStep`, and `PluginStep` carries only `Boolean nodeStep` and `String type`. There is no third state.

Introducing `"Interaction"` as a step-hosting service therefore means changing how a job records what a step is: a job definition format change, cascading into the `PluginStep` domain and a migration, job XML/YAML import/export, every `isNodeStep ?` branch, `ScheduledExecutionService` validation, and SCM export/import — job definitions being what SCM versions. It also means an older Rundeck cannot read a job containing an interaction step.

That is a backward-compatibility surface, and it is separable from the contract this document specifies. Registering under `WorkflowStep` gets the typed SPI, its persistence behavior, and its engine validation with none of it. For context, `ServiceNameConstants` has not gained a service since `PluginGroup` in March 2022.

**What is deferred with it.** Two things depend on a real service entry and are therefore not available under this scheme:

- **A dedicated job-edit palette category.** Interaction steps appear among ordinary workflow steps rather than grouped as human-input steps. The grouping is a UX improvement, not a contract requirement.
- **Registry-level enforcement.** Nothing prevents a class implementing `InteractionPlugin` without the engine recognizing it; recognition is by `instanceof` at dispatch (§5.9) rather than by registration.

Promoting `"Interaction"` to a real service later is additive for plugin authors — `supportedPrimitive`, `prepareRequest` and `onResponse` are unchanged — and is proposed separately, so it can be weighed against the job-format cost on its own merits with working code in hand.

**Packaging.** Configuration properties are declared via `@PluginProperty` exactly as for other Rundeck plugin categories; the `Map<String, Object> configuration` passed to `prepareRequest` is populated from the resolved property values at step-start time. Property validation, defaults, and job-edit rendering are handled by the existing `@PluginProperty` machinery — `InteractionPlugin` adds nothing to that surface. A complete plugin ships as a single JAR containing the plugin class and, where it contributes one, the paired `UIPlugin` class (§6) with its JS/CSS resources. Admins install it by copying the JAR to `libext/`, as for every other Rundeck plugin.

### 5.9 Plugin lifecycle and engine dispatch

Dispatch happens in `StepPluginAdapter`, which already owns the step-plugin execution path. It prefers the typed contract when a plugin offers it:

```java
if (plugin instanceof InteractionPlugin) {
    dispatchInteraction((InteractionPlugin) plugin, stepContext, executionContext, config);
} else {
    plugin.executeStep(stepContext, config);
}
```

1. **Registration.** The plugin registers as a `WorkflowStep` provider like any other step plugin (§5.8). No separate registry.
2. **Step start.** The adapter observes no resume payload, calls `prepareRequest(context, configuration)`, validates the result (§7), and suspends on `request.toSuspendRequest()`. The serialized typed request travels in the envelope metadata (§5.5).
3. **Suspension.** `StepExecutionContext.suspend(SuspendRequest)` is used unchanged. The engine gains one unwrap call; the signature is untouched.
4. **Resume.** A `HILResponse` arrives via the resume path (REST endpoint, polled handler, or scheduled-timeout sweep). The adapter recovers the typed request with `HILRequest.fromSuspendMetadata(...)`, checks that `response.getPrimitive()` matches the request's, runs `request.validateResponse(response)`, and calls `onResponse(context, response)`.
5. **Step completion.** The plugin returns from `onResponse` (success) or throws `StepException` (failure). The engine resumes the workflow with the corresponding step result.

**The `executeStep` bridge.** Because the plugin also implements `StepPlugin`, it must supply `executeStep`. Implementations make it a thin bridge over the typed methods — suspend on `prepareRequest(...).toSuspendRequest()` when there is no resume payload, delegate to `onResponse` when there is — so the plugin stays correct if dispatched as a plain step. When `"Interaction"` becomes a registered service, the bridge is deleted and nothing else changes.

### 5.10 `@Experimental` annotation

```java
@Experimental
public @interface Experimental {
    String since() default "";
    String reason() default "API may change before stable promotion";
}
```

Applied to every new public type in this proposal: `InteractionPlugin`, `HILPrimitives`, `HILRequest`, `HILResponse`, `ConfirmRequest`, `ConfirmResponse`. Signals to plugin authors that the contract is not yet frozen. Removal criterion (informally): two consecutive Rundeck releases with no shape changes and at least one production third-party plugin shipped against the SPI.

### 5.11 Relationship to operator-pause

HIL does **not** cover operator-initiated pause/resume — an operator parking a
running execution from the execution detail page. The two are siblings on the same
engine primitive, entering it through different doors.

```
engine suspend/resume primitive
    ├── HIL — InteractionPlugin, HILRequest / HILResponse
    │     step-initiated, authored into the job definition
    └── operator-pause — PreNextStepHook, OperatorResumePayload
          externally imposed, a core operational control
```

| | HIL (`InteractionPlugin`) | Operator-pause |
|---|---|---|
| Initiated by | the workflow, on reaching the step | an operator, from outside a running execution |
| Parks via | `StepExecutionContext.suspend(SuspendRequest)` | `PreNextStepHook.evaluate()` returning `HookResult.synthesizeSuspension(...)` |
| Authored in | the job definition | nowhere — invisible to the job definition |
| Resume payload | `HILResponse` subtype (`ConfirmResponse`, `"confirmation"`) | `OperatorResumePayload` (`"operator-resume"`), a direct `ResumePayload` |
| UI | plugin-contributed widget | core control on the execution page |

They cannot sensibly be merged. A `HILRequest` exists to carry a typed *question* —
`message`, `decisionSet`, `requiredConfirmerRoles`. Operator-pause asks nothing: its
hook synthesizes a bare `SuspendRequest` with no authored content. Modelling it as an
`InteractionPlugin` would mean a primitive with an empty request type and no plugin
author.

The relationship is worth stating because it is the strongest evidence that the
engine primitive is genuinely general rather than a confirmation feature wearing a
layer of indirection. Two independent consumers, one step-initiated and one
externally imposed, exercise the same suspend/checkpoint/resume path through
different entry points. A primitive with exactly one consumer would not demonstrate
that.

Operator-pause is specified separately and is not part of this SPI.

## 6. UI projection

> **Scope: informative.** This section describes the operator-facing surface as
> built in the reference implementation, so the server-side types in §5 can be judged
> against the experience they exist to produce. It is not part of the plugin-author
> contract, and the dedicated palette category it assumes depends on the deferred
> service registration (§5.8). Expect it to change.

The Java SPI in §5 covers only the server side. To complete the contract, plugins must also project a UI into the Rundeck console — the panel an operator sees while a workflow is paused on a HIL step. This section specifies the client-side surface.

### 6.1 Architecture

Three parts:

- **REST endpoints (engine layer, not part of the plugin contract per se).** A discovery endpoint and a per-primitive submit endpoint: `GET /api/{v}/execution/{id}/interaction` returns `{primitive, request}` or 404 if no interaction is active; `POST /api/{v}/execution/{id}/<primitive>` accepts a `HILResponse` for the named primitive (deserialized via the existing `ResumePayload` Jackson registry). The `<primitive>` segment is the primitive name verbatim — for the built-in confirm primitive, the URL is `POST /api/{v}/execution/{id}/rundeck.confirm`. URL paths preserve the dot in the namespaced name.
- **`UIPlugin` resource contribution (existing Rundeck SPI).** Each `InteractionPlugin` ships a paired `UIPlugin` (`@Plugin(service = "UI")`) that declares `doesApply("execution/show")` and contributes a JS bundle to that page. This is the same mechanism the prototype's `ConfirmUIPlugin` uses today.
- **Client-side Vue component registry (new).** Core ships a small helper, `RundeckHIL`, mounted on the `execution/show` SPA route. The plugin's JS bundle imports `RundeckHIL` and registers a Vue component for its primitive. The host polls `/interaction`, looks up the registered component by `request.primitive`, and mounts it inside the SPA's component tree.

The plugin SPI surface is therefore: server-side `InteractionPlugin` + client-side Vue component registration. The two pieces are paired through the primitive name string.

### 6.2 Component contract

Plugin widgets are Vue components. The contract:

```ts
// TypeScript-shaped declaration; plugins author in JS or TS.
interface HILWidgetProps {
    request: HILRequest;                       // active suspended request envelope
    canRespond: boolean;                       // whether the current user can submit
    lastError: HILValidationError | null;      // populated only on 400 from validateResponse
}

interface HILWidgetEmits {
    respond: (response: HILResponse) => void;
}

interface HILValidationError {
    messages: string[];                        // strings returned by validateResponse
    fields?: Record<string, string>;           // optional per-field, keyed by plugin-defined names
}
```

Props in, events out. The host owns:

- Polling the status endpoint
- Submitting the response via POST `/<primitive>` (the URL constructed from `request.primitive`)
- Optimistic UI for in-flight submissions and error handling
- The suspended/resumed visual framing (the panel chrome around the widget)

The plugin owns:

- Rendering the prompt and primitive-specific input controls
- Validating user input before emitting `respond`
- Constructing the `HILResponse` payload (including the `type` discriminator that matches the response subtype's `JsonTypeName`)

The plugin does not call REST directly. It does not poll. It does not handle CSRF. Those concerns are the host's.

The host computes `canRespond` by intersecting the session user's roles with any role gate declared on the request (e.g., `ConfirmRequest.requiredConfirmerRoles`). Primitives without a role gate set `canRespond = true` for all viewers with execution-read access. Widgets render disabled state when `canRespond` is false — confirm grays the buttons, ask makes the input readonly, attest hides the signing surface — and the host wraps the widget with a status line indicating which roles are required to submit. The panel remains visible regardless: an unauthorized viewer sees that the workflow is paused and what is being asked, just without the means to act on it. Hiding the panel from unauthorized viewers is rejected because it makes paused workflows look stuck to oncall, monitoring, and debugging viewers.

If the request itself contains content that should not be readable by unauthorized viewers (e.g., a prompt referencing sensitive data), that is enforced at the `/interaction` API layer via Rundeck's existing execution-read ACLs — not by hiding the widget client-side.

`lastError` is populated only when a submit attempt returns HTTP 400 carrying validation errors from `HILRequest.validateResponse`. Other failure modes (403, 409, 5xx, network) are surfaced by the host's banner chrome but do not populate `lastError` — the widget is not in the business of representing infrastructure errors. The `fields?` map is convention: a validator can return errors keyed by widget input name (e.g., `{decision: "must be in decisionSet"}`) and the widget can highlight that input. Widgets that ignore `lastError` still get the host's banner-level summary; field-level rendering is opt-in.

### 6.3 Registration API

```js
import { defineComponent } from 'vue';
import { RundeckHIL } from '@rundeck/hil';

const ConfirmWidget = defineComponent({
    props: { request: { type: Object, required: true } },
    emits: ['respond'],
    setup(props, { emit }) {
        // ... render prompt, collect decision, emit response
    },
});

RundeckHIL.registerPrimitiveWidget('rundeck.confirm', ConfirmWidget);
```

`RundeckHIL` is exposed two ways:

- As an importable module from `@rundeck/hil` (resolved by the host's bundler when the plugin's bundle is built with Vue and `@rundeck/hil` externalized).
- As `window.RundeckHIL` for plugin bundles that don't go through a module loader.

Re-registration of the same primitive name overwrites the prior entry; the host logs a warning. The collision behavior at the SPI layer (§7) — both plugins register, routing is by step binding — extends to UI: the most recently registered widget for a primitive is used.

### 6.4 Build expectations

Plugin JS bundles are built with Vue externalized so the host's Vue instance is shared. A minimal Vite config:

```js
// vite.config.js (plugin side)
export default {
    build: {
        lib: { entry: 'src/widget.js', formats: ['iife'], name: 'AcmeHILWidget' },
        rollupOptions: { external: ['vue', '@rundeck/hil'] }
    }
};
```

The compiled bundle goes into `src/main/resources/resources/js/` and is contributed by the plugin's `UIPlugin` exactly as the prototype's `confirm-execution.js` is today.

Plugins not authored in Vue can wrap a custom element via Vue's `defineCustomElement` and register the wrapper component with `RundeckHIL`. Cross-framework support is not a primary goal but is not foreclosed.

**Style isolation.** Plugin styles must be scoped to the plugin's component subtree. SFC plugins use `<style scoped>` (Vue's default — auto-prefixes selectors with a `data-v-<hash>` attribute). Non-SFC plugins (render functions, JSX, plain JS components) prefix all class names with `rundeck-hil-<plugin-id>-`. CSS selectors that target global elements (`body`, `html`, `*`, `:root`, generic tag selectors) are forbidden — use scoped or prefixed selectors only. CSS custom properties defined by the plugin must be scoped (e.g., `--rundeck-hil-confirm-button-bg`), not declared at `:root`. The host's panel chrome operates under the same constraint and does not leak styles into widget-mounted territory; the constraint is symmetric.

### 6.5 Host-side dispatch

An execution has at most one active HIL suspension at a time. The engine serializes HIL requests at the workflow level — even with parallel branches, the suspend mechanism pauses the whole execution at a step boundary. The host can therefore assume a single active widget; multi-suspension is not in scope for this contract.

On the `execution/show` route the SPA:

1. Polls `GET /interaction` (interval TBD by host; not plugin-tunable).
2. When the response transitions from 404 → `{primitive, request}`, looks up `RundeckHIL.widgets[primitive]`.
3. If found, mounts via `<component :is="widgets[primitive]" :request="request" :can-respond="canRespond" :last-error="lastError" @respond="onRespond" />` inside the standard HIL panel slot.
4. On `respond` emit, POSTs the payload to `/<primitive>` (URL constructed from `request.primitive`). On 200, unmounts. On 400 with validation errors from `validateResponse`, sets `lastError = {messages, fields}` and shows a banner; widget stays mounted with user inputs preserved. On 403, 409, 5xx, or network error, shows banner; `lastError` stays null.
5. On the next `respond` emit, clears `lastError` and POSTs again.
6. On the next poll cycle returning 404, unmounts the widget and clears the panel.

### 6.6 Mount-point constraint

The host carves out a single named slot in `execution/show` where HIL widgets mount. Plugin widgets MUST mount into this slot via the registration API; they MUST NOT DOM-attach elsewhere on the page.

Concretely: `execution/show.gsp` reserves a `<div>` whose contents are managed by a Vue island hosted by the SPA. The island owns the HIL panel chrome (header, status indicator, submit/error UI) and dynamically mounts `widgets[request.primitive]` inside it. As the SPA migration progresses and `execution/show` itself becomes a Vue route component, the carveout moves from GSP into the route's template — the contract surface (the registration API and the widget's props/emits) does not change.

What the constraint rules out for plugin widgets:

- Direct DOM mutation outside the slot (injecting into `document.body`, navbar, execution log region, etc.)
- Global event listeners that survive page transitions (use Vue lifecycle hooks scoped to the component)
- Fetch interceptors, router guards, plugins on the host's Vue instance, or anything that affects the SPA broadly
- Loading scripts or styles outside what the paired `UIPlugin` declares — no runtime asset injection

The constraint is enforced by convention, not by sandbox: the JS bundle has page-scope access in principle. Violation is a contract violation surfaced in code review and the `@Experimental` guidance, not blocked at runtime.

Why a carveout rather than free-form injection: the prototype's confirm widget injects itself into the page wherever it finds room, which works for one widget but produces layout conflicts, theming drift, and unpredictable interaction with other UI as the family grows. A reserved slot gives the host control over position, sizing, and neighboring chrome — and gives the operator a consistent place to look for pending interactions regardless of which primitive (or which vendor's plugin) is active.

### 6.7 Fallback for unregistered primitives

If `/interaction` returns a primitive for which no widget has been registered (the plugin jar is installed but its UI bundle failed to load, or the primitive comes from a third party whose UI isn't deployed), the host renders a generic fallback panel: primitive name, raw request JSON in a `<details>` block, and an explicit "no widget registered for primitive '<name>'" message. The execution does not appear stuck silently.

This is also the failure surface for development-time iteration: an in-progress new primitive shows fallback rendering until its widget bundle is wired up.

## 7. Engine validation rules

The engine enforces these invariants in `StepPluginAdapter`, on the interaction dispatch path. Failures surface with explicit error messages.

| Rule | When checked | Error |
|---|---|---|
| `supportedPrimitive()` non-null and non-empty | Step start | `"InteractionPlugin must declare a non-empty supported primitive"` |
| `prepareRequest()` return non-null | Step start | `"InteractionPlugin '<id>' returned null from prepareRequest()"` |
| `request.getPrimitive()` non-null and non-empty | Step start | `"HILRequest must declare a non-empty primitive name"` |
| `request.getPrimitive().equals(supportedPrimitive())` | Step start | `"InteractionPlugin '<id>' returned a request for primitive '<P>' but declares support for '<S>'"` |
| `response.getPrimitive().equals(request.getPrimitive())` | Resume dispatch | `"HIL response primitive '<P_resp>' does not match suspended request primitive '<P_req>' for execution <id>"` |
| `request.validateResponse(response)` returns empty list | Resume dispatch | per-validator error messages joined; surfaced as HTTP 400 to the API caller |
| resume payload is a `HILResponse` | Resume dispatch | `"Expected a HIL response for primitive '<P>' but received resume payload of type '<T>'"` |
| `request` has token, has reason | Step start | applied to the `SuspendRequest` produced by `toSuspendRequest()`, via existing validation |

Because recognition is by `instanceof` rather than registration (§5.8), the registration-time checks a dedicated service would allow are performed at step start instead. The observable behavior is the same; the failure simply surfaces on first use rather than at startup.

Core does not validate primitive names against any allowlist. A plugin declaring `"acme.signature"` is treated identically to one declaring `HILPrimitives.CONFIRM`.

Namespacing (`vendor.primitive`) is a documented convention, not enforced. Two plugins declaring the same primitive name will both register; routing to a specific plugin is determined by the step's plugin binding, not by the primitive name.

## 8. Future primitives

New primitives — well-known names from `HILPrimitives` or third-party names — land as plugin packages, in core or out of tree. Each new primitive ships:

- A concrete `<Primitive>Request extends HILRequest` and `<Primitive>Response implements HILResponse`.
- A `JacksonSubtypeRegistrar` for both new subtypes — the request and the response. The same hook serves both families; no separate registration mechanism is introduced for requests.
- A plugin implementing `InteractionPlugin` whose `supportedPrimitive()` returns the new name.

No core SPI change is required to add a new primitive. If implementation reveals a needed shape change in the SPI itself (e.g., a request-context field that doesn't exist on the envelope), the change is proposed against `@Experimental` types.
