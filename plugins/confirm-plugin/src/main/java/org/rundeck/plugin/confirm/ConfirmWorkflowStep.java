/*
 * Copyright 2026 Rundeck, Inc. (http://rundeck.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.rundeck.plugin.confirm;

import com.dtolabs.rundeck.core.execution.workflow.StepExecutionContext;
import com.dtolabs.rundeck.core.execution.workflow.steps.FailureReason;
import com.dtolabs.rundeck.core.execution.workflow.steps.StepException;
import com.dtolabs.rundeck.core.execution.workflow.steps.StepExecutionResult;
import com.dtolabs.rundeck.plugins.interaction.ConfirmRequest;
import com.dtolabs.rundeck.plugins.interaction.ConfirmResponse;
import com.dtolabs.rundeck.plugins.interaction.HILPrimitives;
import com.dtolabs.rundeck.plugins.interaction.HILRequest;
import com.dtolabs.rundeck.plugins.interaction.HILResponse;
import com.dtolabs.rundeck.plugins.interaction.InteractionPlugin;
import com.dtolabs.rundeck.core.execution.workflow.suspend.ResumePayload;
import com.dtolabs.rundeck.core.execution.workflow.suspend.SuspendRequest;
import com.dtolabs.rundeck.core.execution.workflow.suspend.SuspensionNotAllowedException;
import com.dtolabs.rundeck.core.data.BaseDataContext;
import com.dtolabs.rundeck.core.dispatcher.ContextView;
import com.dtolabs.rundeck.core.plugins.Plugin;
import com.dtolabs.rundeck.plugins.ExecutionEnvironmentConstants;
import com.dtolabs.rundeck.plugins.ServiceNameConstants;
import com.dtolabs.rundeck.plugins.descriptions.PluginDescription;
import com.dtolabs.rundeck.plugins.descriptions.PluginMetadata;
import com.dtolabs.rundeck.plugins.descriptions.PluginProperty;
import com.dtolabs.rundeck.plugins.step.PluginStepContext;
import com.dtolabs.rundeck.plugins.step.StepPlugin;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Wave 5 cycle/workflow-suspend-resume: built-in confirmation workflow step
 * plugin. Suspends the execution pending human confirmation (approve/deny)
 * via the API or UI.
 *
 * <p>On first invocation: constructs a {@link SuspendRequest} with
 * confirmation metadata and calls {@code context.suspend(request)}. The
 * execution parks in {@code waiting} state.
 *
 * <p>On resume invocation: reads the {@link ConfirmResponse} from
 * {@code context.getResumePayload()}, branches on the decision
 * (approve → success, deny → failure, timeout → per timeoutAction).
 *
 * <p>See {@code docs/specs/confirm-workflow-step.md} for the authoritative
 * specification.
 */
@Plugin(name = ConfirmWorkflowStep.PROVIDER_NAME, service = ServiceNameConstants.WorkflowStep)
@PluginDescription(
        title = "Confirm",
        description = "Pause workflow execution and wait for human confirmation (approve/deny). " +
                      "The execution resumes when a user with the 'confirm' ACL action submits " +
                      "a decision via the API or UI."
)
@PluginMetadata(key = ExecutionEnvironmentConstants.ENVIRONMENT_TYPE_KEY, value = ExecutionEnvironmentConstants.LOCAL_RUNNER)
public class ConfirmWorkflowStep implements StepPlugin, InteractionPlugin {

    public static final String PROVIDER_NAME = "confirm";

    /** Confirmation failure reasons. */
    public enum ConfirmFailureReason implements FailureReason {
        ConfirmDenied,
        ConfirmTimeout,
        ConfirmTimeoutFailure,
        ConfirmSuspensionRejected,
        ConfirmUnexpectedPayload
    }

    @PluginProperty(
            title = "Message",
            description = "Prompt text shown to confirmers. Supports ${option.x} interpolation.",
            required = true
    )
    String message;

    @PluginProperty(
            title = "Timeout",
            description = "Maximum wait time (e.g., '24h', '30m'). Default 24h.",
            defaultValue = "24h"
    )
    String timeout;

    @PluginProperty(
            title = "Timeout Action",
            description = "What happens on timeout: deny, approve, or fail.",
            defaultValue = "deny"
    )
    String timeoutAction;

    @PluginProperty(
            title = "Required Confirmer Roles",
            description = "Comma-separated list of roles. Confirmer must hold at least one (in addition to 'confirm' ACL). Leave blank for any confirmer."
    )
    String requiredConfirmerRoles;

    // ------------------------------------------------------------------
    // InteractionPlugin: the typed contract. StepPluginAdapter prefers this
    // path, handing the request to the engine's suspend mechanism and the
    // validated response back on resume.
    // ------------------------------------------------------------------

    @Override
    public String supportedPrimitive() {
        return HILPrimitives.CONFIRM;
    }

    @Override
    public HILRequest prepareRequest(
            final PluginStepContext pluginContext,
            final Map<String, Object> configuration
    ) {
        return ConfirmRequest.builder()
                .token(UUID.randomUUID().toString())
                .reason("awaiting confirmation")
                .timeoutMs(parseTimeout(timeout))
                .message(message != null ? message : "Confirm to continue")
                .criticality("normal")
                .source("job")
                .timeoutAction(timeoutAction != null ? timeoutAction : "deny")
                .decisionSet(Arrays.asList("approve", "deny"))
                .requiredConfirmerRoles(parseRoles(requiredConfirmerRoles))
                .build();
    }

    @Override
    public void onResponse(
            final PluginStepContext pluginContext,
            final HILResponse response
    ) throws StepException {
        if (!(response instanceof ConfirmResponse)) {
            throw new StepException(
                    "Unexpected response type for the confirm primitive: " + response.getPrimitive(),
                    ConfirmFailureReason.ConfirmUnexpectedPayload
            );
        }
        ConfirmResponse payload = (ConfirmResponse) response;

        // The frozen request is the authoritative view of what this step asked.
        HILRequest frozen = null;
        if (pluginContext.getExecutionContext() instanceof StepExecutionContext) {
            frozen = HILRequest.fromSuspendMetadata(
                    ((StepExecutionContext) pluginContext.getExecutionContext()).getSuspendMetadata());
        }
        String frozenTimeoutAction = frozen instanceof ConfirmRequest
                ? ((ConfirmRequest) frozen).getTimeoutAction()
                : (timeoutAction != null ? timeoutAction : "deny");
        if (frozenTimeoutAction == null) {
            frozenTimeoutAction = "deny";
        }

        if (payload.isTimeout()) {
            handleTimeout(pluginContext, frozenTimeoutAction, payload);
            return;
        }

        String decision = payload.getDecision();
        String comment = payload.getComment() != null ? payload.getComment() : "";
        String confirmedBy = payload.getConfirmedBy() != null ? payload.getConfirmedBy() : "";

        publishOutputs(pluginContext, payload, decision, comment, confirmedBy);

        if ("approve".equals(decision)) {
            pluginContext.getLogger().log(
                    2,
                    String.format("[confirm] Confirmed by %s: %s", confirmedBy, comment)
            );
            return;
        }
        throw new StepException(
                "[confirm] Denied by " + confirmedBy + (!comment.isEmpty() ? ": " + comment : ""),
                ConfirmFailureReason.ConfirmDenied
        );
    }

    /**
     * Publish response fields to the step output context so downstream steps can
     * reference them as {@code ${data.confirm.*}}. Written to both the output
     * context (normal engine merge) and the shared data context (resume path,
     * where the normal merge may not fire).
     */
    private void publishOutputs(
            PluginStepContext pluginContext,
            ConfirmResponse payload,
            String decision,
            String comment,
            String confirmedBy
    ) {
        pluginContext.getOutputContext().addOutput(ContextView.global(), "confirm", "decision", decision);
        pluginContext.getOutputContext().addOutput(ContextView.global(), "confirm", "comment", comment);
        pluginContext.getOutputContext().addOutput(ContextView.global(), "confirm", "confirmedBy", confirmedBy);
        if (payload.getConfirmedAt() != null) {
            pluginContext.getOutputContext().addOutput(ContextView.global(), "confirm", "confirmedAt",
                    payload.getConfirmedAt());
        }
        Map<String, String> confirmData = new HashMap<>();
        confirmData.put("decision", decision);
        confirmData.put("comment", comment);
        confirmData.put("confirmedBy", confirmedBy);
        if (payload.getConfirmedAt() != null) {
            confirmData.put("confirmedAt", payload.getConfirmedAt());
        }
        if (pluginContext.getExecutionContext() instanceof StepExecutionContext) {
            ((StepExecutionContext) pluginContext.getExecutionContext())
                    .getSharedDataContext()
                    .merge(ContextView.global(), new BaseDataContext("confirm", confirmData));
        }
    }

    private static List<String> parseRoles(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return Collections.emptyList();
        }
        return Arrays.asList(raw.split("\\s*,\\s*"));
    }

    // ------------------------------------------------------------------
    // StepPlugin: bridge. The plugin registers under the WorkflowStep service
    // so the job definition format is unchanged. StepPluginAdapter routes to
    // the InteractionPlugin contract above; this keeps the plugin correct if
    // it is ever dispatched as a plain step.
    // ------------------------------------------------------------------

    @Override
    public void executeStep(
            final PluginStepContext pluginContext,
            final Map<String, Object> configuration
    ) throws StepException {
        if (!(pluginContext.getExecutionContext() instanceof StepExecutionContext)) {
            throw new StepException(
                    "Confirm step requires a StepExecutionContext",
                    ConfirmFailureReason.ConfirmSuspensionRejected
            );
        }
        StepExecutionContext context = (StepExecutionContext) pluginContext.getExecutionContext();
        ResumePayload rawPayload = context.getResumePayload();

        if (rawPayload == null) {
            try {
                context.suspend(prepareRequest(pluginContext, configuration).toSuspendRequest());
                pluginContext.getLogger().log(2, "[confirm] Requesting suspension: " + message);
            } catch (SuspensionNotAllowedException e) {
                throw new StepException(
                        "Confirmation step cannot suspend: " + e.getMessage(),
                        e,
                        ConfirmFailureReason.ConfirmSuspensionRejected
                );
            }
            return;
        }
        if (!(rawPayload instanceof HILResponse)) {
            throw new StepException(
                    "Unexpected resume payload type: " + rawPayload.getType(),
                    ConfirmFailureReason.ConfirmUnexpectedPayload
            );
        }
        onResponse(pluginContext, (HILResponse) rawPayload);
    }

    private void handleTimeout(
            PluginStepContext context,
            String timeoutAction,
            ConfirmResponse payload
    ) throws StepException {
        switch (timeoutAction) {
            case "approve":
                context.getLogger().log(
                        1,
                        "[confirm] Timed out; auto-approving per configuration"
                );
                return; // success

            case "fail":
                context.getLogger().log(
                        0,
                        "[confirm] Timed out; failing per configuration"
                );
                throw new StepException(
                        "Confirmation timed out (action=fail)",
                        ConfirmFailureReason.ConfirmTimeoutFailure
                );

            case "deny":
            default:
                context.getLogger().log(
                        0,
                        "[confirm] Timed out; denying per configuration"
                );
                throw new StepException(
                        "Confirmation timed out (action=deny)",
                        ConfirmFailureReason.ConfirmTimeout
                );
        }
    }

    private long parseTimeout(String timeoutStr) {
        if (timeoutStr == null || timeoutStr.trim().isEmpty()) {
            return TimeUnit.HOURS.toMillis(24);
        }
        String s = timeoutStr.trim().toLowerCase();
        try {
            if (s.endsWith("h")) {
                return TimeUnit.HOURS.toMillis(Long.parseLong(s.substring(0, s.length() - 1)));
            } else if (s.endsWith("m")) {
                return TimeUnit.MINUTES.toMillis(Long.parseLong(s.substring(0, s.length() - 1)));
            } else if (s.endsWith("s")) {
                return TimeUnit.SECONDS.toMillis(Long.parseLong(s.substring(0, s.length() - 1)));
            } else if (s.endsWith("d")) {
                return TimeUnit.DAYS.toMillis(Long.parseLong(s.substring(0, s.length() - 1)));
            } else {
                return Long.parseLong(s); // assume milliseconds
            }
        } catch (NumberFormatException e) {
            return TimeUnit.HOURS.toMillis(24);
        }
    }
}
