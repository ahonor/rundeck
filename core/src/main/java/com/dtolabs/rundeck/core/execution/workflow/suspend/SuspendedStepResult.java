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

import com.dtolabs.rundeck.core.execution.workflow.steps.FailureReason;
import com.dtolabs.rundeck.core.execution.workflow.steps.StepExecutionResult;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;

/**
 * A {@link StepExecutionResult} sentinel indicating the step has requested
 * suspension. Engine-internal marker; not constructed by plugin authors directly
 * &mdash; plugins call {@code context.suspend(request)} which returns one of these.
 *
 * <p>Contract:
 * <ul>
 *   <li>{@link #isSuccess()} returns {@code false} to prevent legacy code
 *       paths from treating it as a completed successful step.</li>
 *   <li>{@link #isSuspended()} returns {@code true}. The engine's result
 *       aggregation loop MUST check this before {@code isSuccess()}.</li>
 *   <li>{@link #getFailureReason()} returns
 *       {@link SuspendedFailureReason#Suspended} &mdash; distinct from any
 *       {@code StepFailureReason} value so legacy code that inspects the
 *       reason sees a self-describing marker.</li>
 *   <li>{@link #getSuspendRequest()} returns the request constructed by the
 *       step plugin, carrying token, timeout, reason, metadata, payload.</li>
 * </ul>
 *
 * <p>See spec section 2.1 and section 6.1 in
 * {@code docs/specs/workflow-suspend-resume.md}.
 */
public final class SuspendedStepResult implements StepExecutionResult {

    private final SuspendRequest suspendRequest;

    public SuspendedStepResult(SuspendRequest suspendRequest) {
        this.suspendRequest = Objects.requireNonNull(suspendRequest, "suspendRequest");
    }

    @Override
    public boolean isSuccess() {
        return false;
    }

    @Override
    public boolean isSuspended() {
        return true;
    }

    @Override
    public SuspendRequest getSuspendRequest() {
        return suspendRequest;
    }

    @Override
    public Throwable getException() {
        return null;
    }

    @Override
    public Map<String, Object> getFailureData() {
        return Collections.emptyMap();
    }

    @Override
    public FailureReason getFailureReason() {
        return SuspendedFailureReason.Suspended;
    }

    @Override
    public String getFailureMessage() {
        return "step suspended: " + suspendRequest.getReason();
    }

    @Override
    public String toString() {
        return "SuspendedStepResult{" + suspendRequest + '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SuspendedStepResult)) return false;
        SuspendedStepResult that = (SuspendedStepResult) o;
        return Objects.equals(suspendRequest, that.suspendRequest);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(suspendRequest);
    }
}
