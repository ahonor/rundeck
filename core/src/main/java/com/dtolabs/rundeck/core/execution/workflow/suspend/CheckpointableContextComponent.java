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

import com.dtolabs.rundeck.core.common.IFramework;
import com.dtolabs.rundeck.core.execution.component.ContextComponent;

/**
 * A {@link ContextComponent} that can survive a workflow suspension: its state
 * can be serialized into the execution checkpoint and rebuilt on the resume node.
 *
 * <p>Plugins may contribute {@code ContextComponent} entries to the execution
 * context via {@code ExecutionContext.getComponentList()}. By default, plain
 * {@code ContextComponent} entries are NOT considered checkpointable and cause
 * {@link SuspensionPolicy} to reject suspension (invariant I3: checkpoint
 * completeness). To opt in, the component implementation implements this
 * sub-interface and provides:
 *
 * <ul>
 *   <li>{@link #checkpointState()} &mdash; returns a JSON-compatible value
 *       (String, Number, Boolean, Map, List, or null) describing the
 *       component's state. Must round-trip through Jackson without loss.</li>
 *   <li>{@link #restoreState(Object, IFramework)} &mdash; rebuilds component
 *       state from the previously checkpointed value. Called on the resume
 *       node with a fresh framework reference.</li>
 * </ul>
 *
 * <p>Component identity is NOT preserved across suspension &mdash; the
 * instance on the resume node is a distinct JVM object. Plugins that compare
 * components by reference identity will break; plugins that compare by content
 * via {@code equals} will not.
 *
 * <p>See spec section 5.3 in {@code docs/specs/workflow-suspend-resume.md}.
 */
public interface CheckpointableContextComponent<T> extends ContextComponent<T> {

    /**
     * Serialize this component's state into a value Jackson can persist in
     * the execution checkpoint blob. Permitted return types: {@code String},
     * {@code Number}, {@code Boolean}, {@code Map<String, Object>},
     * {@code List<Object>}, or {@code null}. Complex types must flatten to
     * primitives.
     *
     * @return Jackson-serializable state snapshot
     */
    Object checkpointState();

    /**
     * Rebuild this component's state from a previously checkpointed value.
     * Called on the resume node with a fresh {@link IFramework} reference
     * (which may be a different JVM than the one where
     * {@link #checkpointState()} was invoked).
     *
     * @param state     the value previously returned by {@link #checkpointState()}
     * @param framework the local framework on the resume node
     */
    void restoreState(Object state, IFramework framework);
}
