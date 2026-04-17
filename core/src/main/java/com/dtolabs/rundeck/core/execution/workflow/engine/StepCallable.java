/*
 * Copyright 2018 Rundeck, Inc. (http://rundeck.com)
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

package com.dtolabs.rundeck.core.execution.workflow.engine;

import com.dtolabs.rundeck.core.Constants;
import com.dtolabs.rundeck.core.execution.ExecutionContextImpl;
import com.dtolabs.rundeck.core.execution.StepExecutionItem;
import com.dtolabs.rundeck.core.execution.workflow.*;
import com.dtolabs.rundeck.core.execution.workflow.steps.StepExecutionResult;
import com.dtolabs.rundeck.core.execution.workflow.suspend.SuspendRequest;
import com.dtolabs.rundeck.core.execution.workflow.suspend.SuspendedStepResult;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Execute a workflow step
 * @author greg
 * @since 4/28/17
 */
public class StepCallable implements Function<WFSharedContext, BaseWorkflowExecutor.StepResultCapture> {
    private EngineWorkflowExecutor engineWorkflowExecutor;
    private final StepExecutionContext executionContext;
    private final boolean keepgoing;
    private final WorkflowExecutionListener wlistener;
    private final int i;
    private final StepExecutionItem cmd;

    public StepCallable(
            final EngineWorkflowExecutor engineWorkflowExecutor,
            final StepExecutionContext executionContext,
            final boolean keepgoing,
            final WorkflowExecutionListener wlistener,
            final int i,
            final StepExecutionItem cmd
    )
    {
        this.engineWorkflowExecutor = engineWorkflowExecutor;
        this.executionContext = executionContext;
        this.keepgoing = keepgoing;
        this.wlistener = wlistener;
        this.i = i;
        this.cmd = cmd;
    }

    @Override
    public BaseWorkflowExecutor.StepResultCapture apply(final WFSharedContext inputData) {
        // Wave 6: operator-pause check. If an operator has requested a pause
        // via the API, synthesize a suspension BEFORE invoking the actual step.
        // The pause signal is read via the pauseCheckSupplier on the context,
        // which is set by the Grails layer to read from the DB. This is the
        // "between-steps" hook point described in spec §6.3 and
        // operator-pause.md §3.
        if (executionContext instanceof ExecutionContextImpl) {
            ExecutionContextImpl ctxImpl = (ExecutionContextImpl) executionContext;
            if (ctxImpl.isPauseRequested()) {
                Map<String, Object> metadata = new HashMap<>();
                metadata.put("type", "operator-pause");
                metadata.put("requestedAt", new java.util.Date().toString());

                SuspendRequest request = SuspendRequest.builder()
                        .token("operator-pause-" + i)
                        .reason("operator-pause")
                        .waitingFor("operator-resume")
                        .timeoutMs(0) // indefinite
                        .metadata(metadata)
                        .build();
                SuspendedStepResult suspended = new SuspendedStepResult(request);
                // Return a StepResultCapture with the synthesized suspension.
                WorkflowStatusResult suspendStatus = new WorkflowStatusResult() {
                    @Override public boolean isSuccess() { return false; }
                    @Override public String getStatusString() { return null; }
                    @Override public ControlBehavior getControlBehavior() { return null; }
                };
                return new BaseWorkflowExecutor.StepResultCapture(
                        suspended,
                        suspendStatus,
                        inputData != null ? inputData : new WFSharedContext()
                );
            }
        }

        StepExecutionContext newContext =
                ExecutionContextImpl.builder(executionContext)
                                    .sharedDataContext(inputData)
                                    .build();

        final Map<Integer, StepExecutionResult> stepFailedMap = new HashMap<>();
        List<StepExecutionResult> resultList = new ArrayList<>();
        try {
            return engineWorkflowExecutor.executeWorkflowStep(
                    newContext,
                    stepFailedMap,
                    resultList,
                    keepgoing,
                    wlistener,
                    i,
                    cmd
            );
        } catch (Throwable e) {
            String message = String.format(
                    "Exception while executing step [%d]: [%s]",
                    i,
                    e.toString()
            );
            executionContext.getExecutionListener().log(Constants.ERR_LEVEL, message);
            throw new RuntimeException(e);
        }
    }
}
