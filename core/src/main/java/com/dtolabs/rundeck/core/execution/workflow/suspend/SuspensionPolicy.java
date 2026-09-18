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

import com.dtolabs.rundeck.core.execution.component.ContextComponent;
import com.dtolabs.rundeck.core.execution.workflow.WorkflowStrategy;
import com.dtolabs.rundeck.core.logging.StreamingLogWriter;

import java.util.List;

/**
 * Validates that a workflow's execution context and configuration permit
 * suspension. Invoked lazily from {@code StepExecutionContext.suspend(...)}
 * at the moment a step requests suspension (not eagerly at workflow start
 * &mdash; see spec section 12 decision 9).
 *
 * <p>Rejection cases enumerated by invariants I7, I8 and section 5:
 * <ul>
 *   <li><b>I8</b> &mdash; workflow strategy is parallel. Parallel suspend
 *       requires operation-level cancellation and a quiesce protocol; out
 *       of scope in v1.</li>
 *   <li><b>I7</b> &mdash; workflow is a child sub-workflow invocation.
 *       Suspension would block the parent's {@code .join()}; out of scope
 *       in v1.</li>
 *   <li><b>I3</b> &mdash; execution context holds a
 *       {@link ContextComponent} that does not implement
 *       {@link CheckpointableContextComponent}. Checkpoint would silently
 *       lose it on serialization; fail fast instead.</li>
 *   <li><b>Log writer</b> &mdash; configured streaming log writer does not
 *       implement {@link CheckpointableStreamingLogWriter}. Suspension
 *       would leave the log file in an inconsistent state; fail fast.</li>
 * </ul>
 *
 * <p>Wave 0 declares the policy and its rejection cases. The engine's
 * wiring of {@code SuspensionPolicy.validate(...)} into
 * {@code EngineWorkflowExecutor} lands in Wave 1.
 */
public final class SuspensionPolicy {

    private SuspensionPolicy() {
        // static utility
    }

    /**
     * Validate that the given workflow strategy supports suspension. Rejects
     * parallel strategies per invariant I8.
     *
     * @param strategy the workflow strategy in effect
     * @throws SuspensionNotAllowedException if the strategy is parallel
     */
    public static void validateStrategy(WorkflowStrategy strategy) throws SuspensionNotAllowedException {
        if (strategy == null) {
            return;
        }
        final String className = strategy.getClass().getName();
        // Parallel strategies are identified by class name to avoid a core->core
        // cycle with ParallelWorkflowStrategy. The detection is intentionally
        // loose: any strategy whose simple name starts with "Parallel" is rejected.
        final String simpleName = strategy.getClass().getSimpleName();
        if (simpleName.startsWith("Parallel")) {
            throw new SuspensionNotAllowedException(
                    "parallel strategy does not support suspension (strategy=" + className + ")");
        }
    }

    /**
     * Validate that no {@link ContextComponent} in the given list blocks
     * suspension. Plain {@code ContextComponent} entries that do not
     * implement {@link CheckpointableContextComponent} are rejected per
     * invariant I3.
     *
     * @param components the component list from the execution context
     * @throws SuspensionNotAllowedException if any component is non-durable
     */
    public static void validateComponents(List<ContextComponent<?>> components) throws SuspensionNotAllowedException {
        if (components == null || components.isEmpty()) {
            return;
        }
        for (ContextComponent<?> c : components) {
            if (!(c instanceof CheckpointableContextComponent)) {
                throw new SuspensionNotAllowedException(
                        "context component '" + c.getName() + "' (class " + c.getClass().getName()
                                + ") is not checkpointable; implement CheckpointableContextComponent to allow suspension");
            }
        }
    }

    /**
     * Validate that the configured streaming log writer supports suspension.
     * Writers that do not implement {@link CheckpointableStreamingLogWriter}
     * are rejected.
     *
     * @param writer the currently configured streaming log writer (may be
     *               {@code null} if no writer is configured)
     * @throws SuspensionNotAllowedException if the writer does not support
     *                                       suspension
     */
    public static void validateLogWriter(StreamingLogWriter writer) throws SuspensionNotAllowedException {
        if (writer == null) {
            return;
        }
        if (!(writer instanceof CheckpointableStreamingLogWriter)) {
            throw new SuspensionNotAllowedException(
                    "log writer '" + writer.getClass().getName()
                            + "' does not support suspension; implement CheckpointableStreamingLogWriter to allow suspension");
        }
    }

    /**
     * Validate the nested-sub-workflow condition (I7). This check is a
     * placeholder that inspects an explicit {@code nested} flag because the
     * core module does not know how sub-workflow invocation is represented
     * at the execution-context level. Wave 1 wires the real check via a
     * new {@code ExecutionContext.isNestedWorkflow()} accessor or an
     * equivalent signal from the engine.
     *
     * @param nested {@code true} if the workflow is executing as a child of
     *               another workflow
     * @throws SuspensionNotAllowedException if {@code nested} is true
     */
    public static void validateNotNested(boolean nested) throws SuspensionNotAllowedException {
        if (nested) {
            throw new SuspensionNotAllowedException(
                    "sub-workflows may not suspend in v1 (invariant I7)");
        }
    }
}
