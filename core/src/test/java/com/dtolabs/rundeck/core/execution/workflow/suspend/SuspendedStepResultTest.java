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

import com.dtolabs.rundeck.core.execution.workflow.steps.StepExecutionResult;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Wave 0 exit criterion: {@link SuspendedStepResult} state contracts.
 */
public class SuspendedStepResultTest {

    private SuspendRequest sampleRequest() {
        return SuspendRequest.builder()
                .token("t")
                .timeoutMs(1000L)
                .reason("waiting")
                .build();
    }

    @Test
    public void isSuspended_trueAndIsSuccess_false() {
        SuspendedStepResult result = new SuspendedStepResult(sampleRequest());
        assertTrue(result.isSuspended());
        assertFalse(result.isSuccess());
    }

    @Test
    public void failureReason_isSuspendedMarker() {
        SuspendedStepResult result = new SuspendedStepResult(sampleRequest());
        assertEquals(SuspendedFailureReason.Suspended, result.getFailureReason());
    }

    @Test
    public void getSuspendRequest_returnsProvidedRequest() {
        SuspendRequest req = sampleRequest();
        SuspendedStepResult result = new SuspendedStepResult(req);
        assertEquals(req, result.getSuspendRequest());
    }

    @Test
    public void getException_returnsNull() {
        SuspendedStepResult result = new SuspendedStepResult(sampleRequest());
        assertNull(result.getException());
    }

    @Test
    public void failureMessage_includesReason() {
        SuspendRequest req = SuspendRequest.builder()
                .token("t")
                .timeoutMs(1000L)
                .reason("awaiting approval from bob")
                .build();
        SuspendedStepResult result = new SuspendedStepResult(req);
        String msg = result.getFailureMessage();
        assertNotNull(msg);
        assertTrue("failure message should mention reason: " + msg,
                msg.contains("awaiting approval from bob"));
    }

    @Test(expected = NullPointerException.class)
    public void nullRequest_rejected() {
        new SuspendedStepResult(null);
    }

    @Test
    public void isStepExecutionResult_isAssignable() {
        StepExecutionResult result = new SuspendedStepResult(sampleRequest());
        // Upcast must compile and preserve the suspended state.
        assertTrue(result.isSuspended());
        assertFalse(result.isSuccess());
    }
}
