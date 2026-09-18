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

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Logical representation of the JSON blob stored in
 * {@code Execution.checkpoint_data} when a workflow execution is suspended.
 * Jackson round-trips this type via the {@code @JsonCreator} constructor.
 *
 * <p>Shape (see spec section 7.3):
 *
 * <pre>
 * {
 *   "version": 1,
 *   "suspendedStepIndex": 2,
 *   "suspendRequest": { ... },
 *   "contextData": { ... },
 *   "components": [ { "class": "...", "state": ... } ],
 *   "completedStepResults": [ { "stepIndex": 0, "success": true, "data": {} }, ... ]
 * }
 * </pre>
 *
 * <p>Schema version: v1 initial. Readers that encounter an unknown version
 * reject the checkpoint with a clear error. Unknown fields within a known
 * version are ignored ({@code FAIL_ON_UNKNOWN_PROPERTIES=false}) to permit
 * additive evolution within v1.
 *
 * <p>Wave 0 declares the type; Wave 2 populates it from real executions and
 * Wave 4 reads it back during resume.
 */
public final class ExecutionCheckpoint implements Serializable {
    private static final long serialVersionUID = 1L;

    /** Current schema version for checkpoint blobs. */
    public static final int CURRENT_VERSION = 1;

    private final int version;
    private final int suspendedStepIndex;
    private final SuspendRequest suspendRequest;
    private final Map<String, Object> contextData;
    private final List<ComponentSnapshot> components;
    private final List<CompletedStepResult> completedStepResults;

    @JsonCreator
    public ExecutionCheckpoint(
            @JsonProperty("version") int version,
            @JsonProperty("suspendedStepIndex") int suspendedStepIndex,
            @JsonProperty("suspendRequest") SuspendRequest suspendRequest,
            @JsonProperty("contextData") Map<String, Object> contextData,
            @JsonProperty("components") List<ComponentSnapshot> components,
            @JsonProperty("completedStepResults") List<CompletedStepResult> completedStepResults
    ) {
        this.version = version;
        this.suspendedStepIndex = suspendedStepIndex;
        this.suspendRequest = suspendRequest;
        this.contextData = contextData == null
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new HashMap<>(contextData));
        this.components = components == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(components));
        this.completedStepResults = completedStepResults == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(completedStepResults));
    }

    public int getVersion() { return version; }
    public int getSuspendedStepIndex() { return suspendedStepIndex; }
    public SuspendRequest getSuspendRequest() { return suspendRequest; }
    public Map<String, Object> getContextData() { return contextData; }
    public List<ComponentSnapshot> getComponents() { return components; }
    public List<CompletedStepResult> getCompletedStepResults() { return completedStepResults; }

    /**
     * One entry in the {@code components} array: the fully-qualified class
     * name of a {@link CheckpointableContextComponent} implementation plus
     * the state value produced by its
     * {@link CheckpointableContextComponent#checkpointState()} method.
     */
    public static final class ComponentSnapshot implements Serializable {
        private static final long serialVersionUID = 1L;

        private final String className;
        private final Object state;

        @JsonCreator
        public ComponentSnapshot(
                @JsonProperty("class") String className,
                @JsonProperty("state") Object state
        ) {
            this.className = className;
            this.state = state;
        }

        @JsonProperty("class")
        public String getClassName() { return className; }

        public Object getState() { return state; }
    }

    /**
     * One entry in the {@code completedStepResults} array: the step index,
     * its success flag, and any Jackson-serializable data produced by the
     * step (typically shared-context deltas or step-output values).
     *
     * <p>Per spec section 7.3 contract, {@code data} values MUST round-trip
     * through Jackson. Step authors that produce non-serializable values
     * must flatten them before return or register a Jackson module.
     */
    public static final class CompletedStepResult implements Serializable {
        private static final long serialVersionUID = 1L;

        private final int stepIndex;
        private final boolean success;
        private final Map<String, Object> data;

        @JsonCreator
        public CompletedStepResult(
                @JsonProperty("stepIndex") int stepIndex,
                @JsonProperty("success") boolean success,
                @JsonProperty("data") Map<String, Object> data
        ) {
            this.stepIndex = stepIndex;
            this.success = success;
            this.data = data == null
                    ? Collections.emptyMap()
                    : Collections.unmodifiableMap(new HashMap<>(data));
        }

        public int getStepIndex() { return stepIndex; }
        public boolean isSuccess() { return success; }
        public Map<String, Object> getData() { return data; }
    }
}
