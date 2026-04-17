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
import com.dtolabs.rundeck.core.execution.workflow.suspend.ConfirmationPayload;
import com.dtolabs.rundeck.core.execution.workflow.suspend.ResumePayload;
import com.dtolabs.rundeck.core.execution.workflow.suspend.SuspendRequest;
import com.dtolabs.rundeck.core.execution.workflow.suspend.SuspensionNotAllowedException;
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
 * <p>On resume invocation: reads the {@link ConfirmationPayload} from
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
public class ConfirmWorkflowStep implements StepPlugin {

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

    @Override
    public void executeStep(
            final PluginStepContext pluginContext,
            final Map<String, Object> configuration
    ) throws StepException {
        // Access the underlying StepExecutionContext for suspend/resume API
        if (!(pluginContext.getExecutionContext() instanceof StepExecutionContext)) {
            throw new StepException(
                    "Confirm step requires a StepExecutionContext",
                    ConfirmFailureReason.ConfirmSuspensionRejected
            );
        }
        StepExecutionContext context = (StepExecutionContext) pluginContext.getExecutionContext();

        // Check for resume payload — non-null means this is a resume invocation
        ResumePayload rawPayload = context.getResumePayload();

        if (rawPayload == null) {
            // -------------------------------------------------------
            // First invocation: request suspension.
            // -------------------------------------------------------
            long timeoutMs = parseTimeout(timeout);
            String resolvedMessage = message != null ? message : "Confirm to continue";

            // Build metadata (frozen at suspend time per spec §12 decision 14)
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("type", "confirmation");
            metadata.put("message", resolvedMessage);
            metadata.put("decisionSet", Arrays.asList("approve", "deny"));
            metadata.put("timeoutAction", timeoutAction != null ? timeoutAction : "deny");
            if (requiredConfirmerRoles != null && !requiredConfirmerRoles.trim().isEmpty()) {
                metadata.put("requiredConfirmerRoles",
                        Arrays.asList(requiredConfirmerRoles.split("\\s*,\\s*")));
            }

            SuspendRequest request = SuspendRequest.builder()
                    .token(UUID.randomUUID().toString())
                    .reason("awaiting confirmation")
                    .waitingFor("user-confirmation")
                    .timeoutMs(timeoutMs)
                    .metadata(metadata)
                    .build();

            try {
                // Call suspend on the underlying StepExecutionContext. The
                // returned SuspendedStepResult is stored as a pending
                // suspension on the ExecutionContextImpl; StepPluginAdapter
                // detects it after this void-returning executeStep() exits
                // and returns the SuspendedStepResult to the engine.
                context.suspend(request);
                pluginContext.getLogger().log(
                        2,
                        "[confirm] Requesting suspension: " + resolvedMessage
                );
                return;
            } catch (SuspensionNotAllowedException e) {
                throw new StepException(
                        "Confirmation step cannot suspend: " + e.getMessage(),
                        e,
                        ConfirmFailureReason.ConfirmSuspensionRejected
                );
            }
        }

        // -------------------------------------------------------
        // Resume invocation: the event has been delivered.
        // -------------------------------------------------------

        // Read frozen metadata from the context
        Map<String, Object> frozen = context.getSuspendMetadata();
        String frozenTimeoutAction = frozen != null
                ? (String) frozen.getOrDefault("timeoutAction", "deny")
                : "deny";

        if (rawPayload instanceof ConfirmationPayload) {
            ConfirmationPayload payload = (ConfirmationPayload) rawPayload;

            if (payload.isTimeout()) {
                handleTimeout(pluginContext, frozenTimeoutAction, payload);
                return;
            }

            String decision = payload.getDecision();
            if ("approve".equals(decision)) {
                pluginContext.getLogger().log(
                        2,
                        String.format("[confirm] Confirmed by %s (roles: %s): %s",
                                payload.getConfirmedBy(),
                                payload.getConfirmerRoles(),
                                payload.getComment() != null ? payload.getComment() : "")
                );
                // Success: return normally
                return;
            } else {
                // deny or any other non-success decision
                pluginContext.getLogger().log(
                        0,
                        String.format("[confirm] Rejected by %s: %s (decision: %s)",
                                payload.getConfirmedBy(),
                                payload.getComment() != null ? payload.getComment() : "",
                                decision)
                );
                throw new StepException(
                        "Confirmation denied: " + decision,
                        ConfirmFailureReason.ConfirmDenied
                );
            }
        } else {
            // Unexpected payload type
            pluginContext.getLogger().log(
                    0,
                    "[confirm] Unexpected resume payload type: " +
                            (rawPayload != null ? rawPayload.getType() : "null")
            );
            throw new StepException(
                    "Unexpected resume payload type: " +
                            (rawPayload != null ? rawPayload.getType() : "null"),
                    ConfirmFailureReason.ConfirmUnexpectedPayload
            );
        }
    }

    private void handleTimeout(
            PluginStepContext context,
            String timeoutAction,
            ConfirmationPayload payload
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
