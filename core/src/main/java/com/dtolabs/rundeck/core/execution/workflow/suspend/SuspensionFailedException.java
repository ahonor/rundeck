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
 * Thrown when a suspension attempt was permitted by {@link SuspensionPolicy}
 * but subsequently failed during persistence: checkpoint serialization error,
 * log flush/fsync failure, DB commit failure.
 *
 * <p>Distinct from {@link SuspensionNotAllowedException}, which is raised
 * pre-flight and means the workflow is not eligible for suspension at all.
 * This exception is raised after the suspension was allowed but could not
 * be completed; the step typically transitions to {@code failed} and the
 * execution takes the normal failure path (see spec section 10.1).
 */
public class SuspensionFailedException extends Exception {
    private static final long serialVersionUID = 1L;

    public SuspensionFailedException(String message) {
        super(message);
    }

    public SuspensionFailedException(String message, Throwable cause) {
        super(message, cause);
    }
}
