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
package com.dtolabs.rundeck.core.execution.workflow;

import com.dtolabs.rundeck.core.execution.workflow.steps.StepExecutionResult;
import com.dtolabs.rundeck.core.execution.workflow.suspend.SuspendRequest;
import com.dtolabs.rundeck.core.execution.workflow.suspend.SuspendedStepResult;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Wave 1 unit tests for {@link BaseWorkflowExecutor.BaseWorkflowExecutionResult}
 * suspended-aware behavior. Lives in the same package as
 * {@code BaseWorkflowExecutor} so it can access the protected nested
 * {@code BaseWorkflowExecutionResult} and package-private
 * {@code BaseWorkflowStatusResult} types.
 *
 * <p>Engine-loop integration is exercised by {@code EngineWorkflowExecutorSpec}
 * (Spock) in this same package. These Java unit tests verify the result class's
 * own contracts in isolation from the engine loop.
 */
public class WorkflowExecutionResultSuspendTest {

    private SuspendRequest sampleRequest(String token) {
        return SuspendRequest.builder()
                .token(token)
                .timeoutMs(60_000L)
                .reason("awaiting confirmation")
                .waitingFor("user-confirmation")
                .build();
    }

    private BaseWorkflowExecutor.BaseWorkflowExecutionResult buildSuspendedResult(
            List<StepExecutionResult> results,
            List<SuspendRequest> suspendRequests
    ) {
        BaseWorkflowExecutor.BaseWorkflowStatusResult status =
                new BaseWorkflowExecutor.BaseWorkflowStatusResult(
                        false,
                        null,
                        ControlBehavior.Continue,
                        null
                );
        return new BaseWorkflowExecutor.BaseWorkflowExecutionResult(
                results,
                new HashMap<>(),
                new HashMap<>(),
                null,
                status,
                null,
                true,
                suspendRequests
        );
    }

    private BaseWorkflowExecutor.BaseWorkflowExecutionResult buildNonSuspendedResult() {
        BaseWorkflowExecutor.BaseWorkflowStatusResult status =
                new BaseWorkflowExecutor.BaseWorkflowStatusResult(
                        true,
                        null,
                        ControlBehavior.Continue,
                        null
                );
        return new BaseWorkflowExecutor.BaseWorkflowExecutionResult(
                new ArrayList<>(),
                new HashMap<>(),
                new HashMap<>(),
                null,
                status,
                null
        );
    }

    @Test
    public void suspendedResult_isSuspendedTrue_isSuccessFalse() {
        SuspendRequest req = sampleRequest("t1");
        List<StepExecutionResult> results = new ArrayList<>();
        results.add(new SuspendedStepResult(req));

        WorkflowExecutionResult wfResult = buildSuspendedResult(
                results,
                Collections.singletonList(req)
        );

        assertTrue("workflow result must be suspended", wfResult.isSuspended());
        assertFalse("workflow result must not be success", wfResult.isSuccess());
        assertEquals("must carry one suspend request", 1, wfResult.getSuspendRequests().size());
        assertEquals(req, wfResult.getSuspendRequests().get(0));
    }

    @Test
    public void suspendedResult_hasEmptyStepFailures() {
        SuspendRequest req = sampleRequest("t2");
        WorkflowExecutionResult wfResult = buildSuspendedResult(
                new ArrayList<StepExecutionResult>(),
                Collections.singletonList(req)
        );

        assertNotNull(wfResult.getStepFailures());
        assertTrue("stepFailures must be empty for a suspension",
                wfResult.getStepFailures().isEmpty());
    }

    @Test
    public void nonSuspendedResult_defaultsToNotSuspended() {
        WorkflowExecutionResult wfResult = buildNonSuspendedResult();

        assertFalse(wfResult.isSuspended());
        assertTrue(wfResult.isSuccess());
        assertNotNull(wfResult.getSuspendRequests());
        assertTrue(wfResult.getSuspendRequests().isEmpty());
    }

    @Test
    public void toString_includesSuspendedMarker() {
        SuspendRequest req = sampleRequest("t3");
        WorkflowExecutionResult wfResult = buildSuspendedResult(
                new ArrayList<StepExecutionResult>(),
                Collections.singletonList(req)
        );

        String s = wfResult.toString();
        assertNotNull(s);
        assertTrue("toString should name suspended state: " + s,
                s.contains("suspended"));
        assertTrue("toString should name suspendRequests count: " + s,
                s.contains("suspendRequests"));
    }

    @Test
    public void suspendRequests_listIsImmutable() {
        List<SuspendRequest> mutable = new ArrayList<>();
        mutable.add(sampleRequest("tA"));

        WorkflowExecutionResult wfResult = buildSuspendedResult(
                new ArrayList<StepExecutionResult>(),
                mutable
        );

        // Post-construction mutation of the caller's list must not affect the
        // result's view. The result must defensively copy.
        mutable.add(sampleRequest("tB"));
        assertEquals(1, wfResult.getSuspendRequests().size());

        // And the returned list itself must be unmodifiable.
        try {
            wfResult.getSuspendRequests().add(sampleRequest("tC"));
            throw new AssertionError("expected getSuspendRequests() to return an unmodifiable list");
        } catch (UnsupportedOperationException expected) {
            // pass
        }
    }

    @Test
    public void partialResults_preservedInSuspendedResult() {
        // Simulate a 3-step workflow where step 3 suspends: the result set
        // should contain prior step results plus the suspended step.
        SuspendRequest req = sampleRequest("t4");
        List<StepExecutionResult> results = new ArrayList<>();
        results.add(new SuspendedStepResult(sampleRequest("dummy-completed-1")));
        results.add(new SuspendedStepResult(sampleRequest("dummy-completed-2")));
        results.add(new SuspendedStepResult(req));

        WorkflowExecutionResult wfResult = buildSuspendedResult(
                results,
                Collections.singletonList(req)
        );

        assertEquals(3, wfResult.getResultSet().size());
        assertTrue(wfResult.getResultSet().get(2).isSuspended());
        assertTrue(wfResult.isSuspended());
    }
}
