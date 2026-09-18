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

/**
 * Thrown by {@link SuspensionPolicy} when a suspension attempt is rejected
 * because the workflow or its context is not eligible for suspension.
 *
 * <p>Distinct causes:
 * <ul>
 *   <li>Workflow uses the parallel strategy (invariant I8 forbids it in v1).</li>
 *   <li>Workflow is executing as a sub-workflow (invariant I7 forbids it in v1).</li>
 *   <li>Execution context contains a {@code ContextComponent} that does not
 *       implement {@link CheckpointableContextComponent}.</li>
 *   <li>Configured streaming log writer does not implement
 *       {@link CheckpointableStreamingLogWriter}.</li>
 * </ul>
 *
 * <p>The exception message names the specific cause so plugin authors and
 * operators can diagnose and remediate. See spec section 10.1.
 */
public class SuspensionNotAllowedException extends Exception {
    private static final long serialVersionUID = 1L;

    public SuspensionNotAllowedException(String message) {
        super(message);
    }

    public SuspensionNotAllowedException(String message, Throwable cause) {
        super(message, cause);
    }
}
