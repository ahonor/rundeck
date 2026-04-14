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

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Wave 0 exit criterion: {@link ExecutionCheckpoint} Jackson round-trip.
 */
public class ExecutionCheckpointTest {

    private ObjectMapper mapper() {
        ObjectMapper m = new ObjectMapper();
        m.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        return m;
    }

    @Test
    public void roundTrip_preservesAllFields() throws Exception {
        SuspendRequest request = SuspendRequest.builder()
                .token("tok")
                .timeoutMs(60_000L)
                .reason("test")
                .waitingFor("user-confirmation")
                .build();

        Map<String, Object> contextData = new HashMap<>();
        contextData.put("user", "alice");
        contextData.put("project", "ops-deploy");

        ExecutionCheckpoint.ComponentSnapshot comp =
                new ExecutionCheckpoint.ComponentSnapshot(
                        "com.example.MyComponent",
                        Collections.singletonMap("state-key", "state-value"));

        Map<String, Object> stepData = new HashMap<>();
        stepData.put("output", "build-artifact-v1.42.3");

        ExecutionCheckpoint.CompletedStepResult done =
                new ExecutionCheckpoint.CompletedStepResult(0, true, stepData);

        ExecutionCheckpoint original = new ExecutionCheckpoint(
                ExecutionCheckpoint.CURRENT_VERSION,
                1,
                request,
                contextData,
                Collections.singletonList(comp),
                Collections.singletonList(done)
        );

        ObjectMapper m = mapper();
        String json = m.writeValueAsString(original);
        assertNotNull(json);

        ExecutionCheckpoint restored = m.readValue(json, ExecutionCheckpoint.class);
        assertEquals(ExecutionCheckpoint.CURRENT_VERSION, restored.getVersion());
        assertEquals(1, restored.getSuspendedStepIndex());
        assertEquals(request, restored.getSuspendRequest());
        assertEquals("alice", restored.getContextData().get("user"));
        assertEquals(1, restored.getComponents().size());
        assertEquals("com.example.MyComponent", restored.getComponents().get(0).getClassName());
        assertEquals(1, restored.getCompletedStepResults().size());
        assertEquals(0, restored.getCompletedStepResults().get(0).getStepIndex());
        assertTrue(restored.getCompletedStepResults().get(0).isSuccess());
        assertEquals("build-artifact-v1.42.3",
                restored.getCompletedStepResults().get(0).getData().get("output"));
    }

    @Test
    public void roundTrip_withEmptyCollections() throws Exception {
        ExecutionCheckpoint original = new ExecutionCheckpoint(
                ExecutionCheckpoint.CURRENT_VERSION,
                0,
                SuspendRequest.builder().token("t").timeoutMs(1L).build(),
                null,
                null,
                null
        );

        ObjectMapper m = mapper();
        String json = m.writeValueAsString(original);
        ExecutionCheckpoint restored = m.readValue(json, ExecutionCheckpoint.class);

        assertTrue(restored.getContextData().isEmpty());
        assertTrue(restored.getComponents().isEmpty());
        assertTrue(restored.getCompletedStepResults().isEmpty());
    }

    @Test
    public void unknownVersion_isReadable_legacyCheckCanInspectVersionField() throws Exception {
        // The type itself does not reject unknown versions; that rejection
        // happens in the resume worker after reading the version field. Wave 0
        // merely ensures the version field is round-tripped so the worker can
        // inspect it.
        ExecutionCheckpoint future = new ExecutionCheckpoint(
                999,
                0,
                SuspendRequest.builder().token("t").timeoutMs(1L).build(),
                Collections.emptyMap(),
                Collections.emptyList(),
                Collections.emptyList()
        );
        String json = mapper().writeValueAsString(future);
        ExecutionCheckpoint restored = mapper().readValue(json, ExecutionCheckpoint.class);
        assertEquals(999, restored.getVersion());
    }

    @Test
    public void completedStepResults_preserveOrder() throws Exception {
        ExecutionCheckpoint.CompletedStepResult s0 =
                new ExecutionCheckpoint.CompletedStepResult(0, true, null);
        ExecutionCheckpoint.CompletedStepResult s1 =
                new ExecutionCheckpoint.CompletedStepResult(1, true, null);
        ExecutionCheckpoint.CompletedStepResult s2 =
                new ExecutionCheckpoint.CompletedStepResult(2, false, null);

        ExecutionCheckpoint original = new ExecutionCheckpoint(
                ExecutionCheckpoint.CURRENT_VERSION,
                3,
                SuspendRequest.builder().token("t").timeoutMs(1L).build(),
                null,
                null,
                Arrays.asList(s0, s1, s2)
        );

        String json = mapper().writeValueAsString(original);
        ExecutionCheckpoint restored = mapper().readValue(json, ExecutionCheckpoint.class);

        assertEquals(3, restored.getCompletedStepResults().size());
        assertEquals(0, restored.getCompletedStepResults().get(0).getStepIndex());
        assertEquals(1, restored.getCompletedStepResults().get(1).getStepIndex());
        assertEquals(2, restored.getCompletedStepResults().get(2).getStepIndex());
        assertTrue(restored.getCompletedStepResults().get(0).isSuccess());
        assertTrue(restored.getCompletedStepResults().get(1).isSuccess());
        assertTrue(!restored.getCompletedStepResults().get(2).isSuccess());
    }
}
