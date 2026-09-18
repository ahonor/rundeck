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
package com.dtolabs.rundeck.core.execution.workflow.suspend;

import com.dtolabs.rundeck.core.execution.workflow.StepExecutionContext;

/**
 * Wave 6 cycle/workflow-suspend-resume: extension point for synthesized
 * suspensions between workflow steps. Hooks are invoked after each step's
 * result is processed in {@code EngineWorkflowExecutor}'s aggregation loop
 * and BEFORE the next step begins. Consumers (like the operator-pause
 * feature) use this to inject suspensions based on external state.
 *
 * <p>Contract (spec §6.3):
 * <ul>
 *   <li>Hooks MUST be cheap (at most one DB read or in-memory check).</li>
 *   <li>Hooks MUST be idempotent.</li>
 *   <li>Hooks MUST NOT have side effects beyond reading state. The engine
 *       performs the actual suspension.</li>
 *   <li>Hooks are NOT invoked after the final step of a workflow (no
 *       boundary at termination).</li>
 * </ul>
 *
 * <p>Registration: built-in hooks are discovered at engine construction
 * time. Dynamic registration is not supported in v1.
 *
 * @see HookResult
 * @see com.dtolabs.rundeck.core.execution.workflow.EngineWorkflowExecutor
 */
public interface PreNextStepHook {

    /**
     * Evaluate whether the workflow should suspend before starting the
     * next step.
     *
     * @param context              the execution context
     * @param justCompletedStepNum the 1-based step number that just completed
     * @param totalStepCount       total number of steps in the workflow
     * @return {@link HookResult#PROCEED} to continue normally, or a
     *         {@link HookResult#synthesizeSuspension(SuspendRequest)} to
     *         park the execution at this boundary
     */
    HookResult evaluate(
            StepExecutionContext context,
            int justCompletedStepNum,
            int totalStepCount
    );
}
