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

/**
 * Distinct {@link FailureReason} category for {@link SuspendedStepResult}.
 *
 * <p>A suspended step is not a true failure; the engine detects the
 * {@code isSuspended()} flag before inspecting {@code getFailureReason()}.
 * However, {@link com.dtolabs.rundeck.core.execution.workflow.steps.StepExecutionResult}
 * requires a {@code FailureReason} to be returned, and legacy code paths that
 * have not been taught about suspension may inspect it. Returning this enum
 * rather than {@code null} or a StepFailureReason value makes the intent
 * self-describing: the result is in the suspended state, not a failure.
 */
public enum SuspendedFailureReason implements FailureReason {
    /** The step requested suspension via {@code context.suspend(...)}. */
    Suspended,
    /** The suspension attempt was rejected by {@code SuspensionPolicy}. */
    SuspensionNotAllowed,
    /** The suspension attempt failed after being permitted (e.g., log flush failure). */
    SuspensionFailed;

    @Override
    public String toString() {
        return name();
    }
}
