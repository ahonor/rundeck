/*
 * Copyright 2016 SimplifyOps, Inc. (http://simplifyops.com)
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

/*
* StepExecutionContext.java
* 
* User: Greg Schueler <a href="mailto:greg@dtosolutions.com">greg@dtosolutions.com</a>
* Created: 11/27/12 4:17 PM
* 
*/
package com.dtolabs.rundeck.core.execution.workflow;

import com.dtolabs.rundeck.core.common.INodeSet;
import com.dtolabs.rundeck.core.execution.ExecutionContext;
import com.dtolabs.rundeck.core.execution.workflow.steps.StepExecutionResult;
import com.dtolabs.rundeck.core.execution.workflow.suspend.ResumePayload;
import com.dtolabs.rundeck.core.execution.workflow.suspend.SuspendRequest;
import com.dtolabs.rundeck.core.execution.workflow.suspend.SuspendedStepResult;
import com.dtolabs.rundeck.core.execution.workflow.suspend.SuspensionNotAllowedException;

import java.util.Collections;
import java.util.List;
import java.util.Map;


/**
 * StepExecutionContext is ...
 *
 * @author Greg Schueler <a href="mailto:greg@dtosolutions.com">greg@dtosolutions.com</a>
 */
public interface StepExecutionContext extends ExecutionContext {
    /**
     * @return the step being executed.
     */
    public int getStepNumber();

    /**
     * @return the stack of step numbers within the larger workflow context.
     */
    public List<Integer> getStepContext();

    /**
     * @return object to control workflow
     */
    public FlowControl getFlowControl();

    /**
     *
     * @return filtered node set
     */
    public INodeSet filteredNodes();

    /**
     * Returns the resume payload if this invocation is a resume of a
     * previously suspended step. Returns {@code null} on first invocation.
     *
     * <p>The concrete subtype (e.g., {@code ConfirmationPayload},
     * {@code OperatorResumePayload}) is determined by the
     * {@link ResumePayload#getType()} discriminator; plugins downcast after
     * checking {@code getType()}.
     *
     * <p>Default returns {@code null}, so existing execution-context
     * implementations are unchanged. The real implementation in
     * {@code ExecutionContextImpl} (Wave 2) overrides this to return the
     * payload populated by the resume worker.
     *
     * <p>See spec section 5.1 in {@code docs/specs/workflow-suspend-resume.md}.
     */
    default ResumePayload getResumePayload() {
        return null;
    }

    /**
     * Returns the frozen suspend metadata for this execution, populated on
     * resume invocations from the {@code Execution.suspend_metadata} DB
     * column. Returns an empty map on first invocation.
     *
     * <p>Plugins MUST read configuration from this map on resume rather than
     * re-reading the job definition, to honor the configuration-freeze
     * contract (spec section 12 decision 14): a mid-flight edit to the job
     * definition must not retroactively change resumed behavior.
     *
     * <p>Default returns an empty map.
     */
    default Map<String, Object> getSuspendMetadata() {
        return Collections.emptyMap();
    }

    /**
     * Request that this execution suspend, releasing its thread, until the
     * external event described by the request is delivered.
     *
     * <p>Canonical plugin pattern:
     *
     * <pre>
     * if (context.getResumePayload() == null) {
     *     // First invocation: request suspension.
     *     return context.suspend(SuspendRequest.builder()
     *             .token(...)
     *             .reason("awaiting approval")
     *             .timeoutMs(TimeUnit.HOURS.toMillis(24))
     *             .metadata(Map.of("type", "confirmation", "message", msg))
     *             .build());
     * }
     * // Resume invocation: inspect payload and return normally.
     * ResumePayload payload = context.getResumePayload();
     * // ...
     * </pre>
     *
     * <p>The returned {@link StepExecutionResult} is a sentinel
     * ({@link SuspendedStepResult}) that the engine recognizes as a
     * suspension signal. The step author writes
     * {@code return context.suspend(request);} as the last statement of
     * the step's execute method.
     *
     * <p>Default throws {@link UnsupportedOperationException}, so
     * execution-context implementations that do not support suspension
     * (e.g., mocks) surface a clear error. Production implementations in
     * {@code ExecutionContextImpl} (Wave 2) override this to call
     * {@code SuspensionPolicy.validate(...)} and return a
     * {@link SuspendedStepResult}.
     *
     * @throws SuspensionNotAllowedException if the workflow is not suspendable
     *         (parallel strategy, sub-workflow, non-checkpointable
     *         components, or non-checkpointable log writer).
     */
    default StepExecutionResult suspend(SuspendRequest request) throws SuspensionNotAllowedException {
        throw new UnsupportedOperationException(
                "This execution context does not support suspension");
    }

}
