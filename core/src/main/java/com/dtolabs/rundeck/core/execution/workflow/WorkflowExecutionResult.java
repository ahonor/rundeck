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
* WorkflowExecutionResult.java
* 
* User: Greg Schueler <a href="mailto:greg@dtosolutions.com">greg@dtosolutions.com</a>
* Created: 3/23/11 2:06 PM
* 
*/
package com.dtolabs.rundeck.core.execution.workflow;

import com.dtolabs.rundeck.core.execution.ExceptionStatusResult;
import com.dtolabs.rundeck.core.execution.workflow.steps.StepExecutionResult;
import com.dtolabs.rundeck.core.execution.workflow.suspend.SuspendRequest;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * WorkflowExecutionResult contains a list of workflow item results, indexed by workflow step number, and
 * node names to failure messages.
 *
 *
 * @author Greg Schueler <a href="mailto:greg@dtosolutions.com">greg@dtosolutions.com</a>
 */
public interface WorkflowExecutionResult extends ExceptionStatusResult, WorkflowStatusResult, WorkflowDataResult {
    /**
     * @return list of step results
     */
    public List<StepExecutionResult> getResultSet();
    /**
     * @return map of workflow item failures, keyed by node name
     */
    public Map<String, Collection<StepExecutionResult>> getNodeFailures();
    /**
     * @return map of workflow item failures, keyed by node name
     */
    public Map<Integer, StepExecutionResult> getStepFailures();

    /**
     * Whether this workflow result represents a suspended execution. When
     * {@code true}, the overall workflow is neither successful nor failed;
     * it has parked at a step boundary and will resume later.
     *
     * <p>The engine's aggregation loop sets this to {@code true} when any
     * step in the workflow returned a {@link StepExecutionResult} with
     * {@code isSuspended() == true}. Workflow-end listener callbacks MUST
     * be suppressed when this is {@code true}; see spec section 6.1.
     *
     * <p>Default returns {@code false}, so existing implementations are
     * unchanged.
     */
    default boolean isSuspended() {
        return false;
    }

    /**
     * Returns the list of {@link SuspendRequest}s carried by suspended steps
     * in this workflow. For sequential workflows (the only kind supported in
     * v1), this list has at most one entry. Default returns an empty list.
     */
    default List<SuspendRequest> getSuspendRequests() {
        return Collections.emptyList();
    }
}
