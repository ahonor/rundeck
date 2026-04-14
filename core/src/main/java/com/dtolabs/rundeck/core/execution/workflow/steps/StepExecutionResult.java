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
* StepExecutionResult.java
* 
* User: Greg Schueler <a href="mailto:greg@dtosolutions.com">greg@dtosolutions.com</a>
* Created: 11/2/12 11:46 AM
* 
*/
package com.dtolabs.rundeck.core.execution.workflow.steps;

import com.dtolabs.rundeck.core.execution.ExceptionStatusResult;
import com.dtolabs.rundeck.core.execution.workflow.DataOutput;
import com.dtolabs.rundeck.core.execution.workflow.OutputContext;
import com.dtolabs.rundeck.core.execution.workflow.suspend.SuspendRequest;

import java.util.Map;


/**
 * StepExecutionResult is result of a step.
 *
 * @author Greg Schueler <a href="mailto:greg@dtosolutions.com">greg@dtosolutions.com</a>
 */
public interface StepExecutionResult extends ExceptionStatusResult {
    public Map<String,Object> getFailureData();

    public FailureReason getFailureReason();
    public String getFailureMessage();

    /**
     * Whether this result represents a suspended step. When {@code true}, the
     * engine's result aggregation loop MUST treat it as a suspension signal,
     * NOT as a failure, even though {@link #isSuccess()} returns {@code false}.
     *
     * <p>The check must be performed BEFORE inspecting {@link #isSuccess()} or
     * {@link #getFailureReason()}. Default returns {@code false}, so existing
     * implementations are unchanged.
     *
     * <p>See spec section 2.1 and section 6.1 in
     * {@code docs/specs/workflow-suspend-resume.md}.
     */
    default boolean isSuspended() {
        return false;
    }

    /**
     * Returns the {@link SuspendRequest} carried by this result if
     * {@link #isSuspended()} is {@code true}, otherwise {@code null}. Default
     * returns {@code null} so existing implementations are unchanged.
     */
    default SuspendRequest getSuspendRequest() {
        return null;
    }
}
