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
import com.dtolabs.rundeck.core.logging.LogEvent;
import com.dtolabs.rundeck.core.logging.StreamingLogWriter;
import org.junit.Test;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Wave 0 exit criterion: {@link SuspensionPolicy} rejection cases for
 * parallel strategy, non-checkpointable components, non-checkpointable log
 * writers, and nested sub-workflow context.
 */
public class SuspensionPolicyTest {

    // ---------- validateComponents ----------

    @Test
    public void validateComponents_passesWhenEmpty() throws Exception {
        SuspensionPolicy.validateComponents(Collections.emptyList());
        SuspensionPolicy.validateComponents(null);
    }

    @Test
    public void validateComponents_passesWhenAllCheckpointable() throws Exception {
        ContextComponent<?> durable = new DurableComponent();
        SuspensionPolicy.validateComponents(Collections.singletonList(durable));
    }

    @Test
    public void validateComponents_rejectsNonCheckpointable() {
        ContextComponent<?> plain = ContextComponent.with("plain", "value", String.class);
        try {
            SuspensionPolicy.validateComponents(Collections.singletonList(plain));
            fail("expected SuspensionNotAllowedException");
        } catch (SuspensionNotAllowedException e) {
            assertTrue("message should name the component: " + e.getMessage(),
                    e.getMessage().contains("plain"));
            assertTrue("message should mention CheckpointableContextComponent",
                    e.getMessage().contains("CheckpointableContextComponent"));
        }
    }

    @Test
    public void validateComponents_rejectsIfAnyNonCheckpointable() {
        ContextComponent<?> durable = new DurableComponent();
        ContextComponent<?> plain = ContextComponent.with("nondurable", "v", String.class);
        try {
            SuspensionPolicy.validateComponents(Arrays.asList(durable, plain));
            fail("expected SuspensionNotAllowedException");
        } catch (SuspensionNotAllowedException e) {
            assertTrue(e.getMessage().contains("nondurable"));
        }
    }

    // ---------- validateLogWriter ----------

    @Test
    public void validateLogWriter_passesWhenCheckpointable() throws Exception {
        CheckpointableStreamingLogWriter writer = new CheckpointableMockWriter();
        SuspensionPolicy.validateLogWriter(writer);
    }

    @Test
    public void validateLogWriter_passesWhenNull() throws Exception {
        SuspensionPolicy.validateLogWriter(null);
    }

    @Test
    public void validateLogWriter_rejectsPlainStreamingLogWriter() {
        StreamingLogWriter writer = new PlainMockWriter();
        try {
            SuspensionPolicy.validateLogWriter(writer);
            fail("expected SuspensionNotAllowedException");
        } catch (SuspensionNotAllowedException e) {
            assertTrue(e.getMessage().contains("does not support suspension"));
            assertTrue(e.getMessage().contains(PlainMockWriter.class.getName()));
        }
    }

    // ---------- validateNotNested ----------

    @Test
    public void validateNotNested_passesWhenFalse() throws Exception {
        SuspensionPolicy.validateNotNested(false);
    }

    @Test
    public void validateNotNested_rejectsWhenTrue() {
        try {
            SuspensionPolicy.validateNotNested(true);
            fail("expected SuspensionNotAllowedException");
        } catch (SuspensionNotAllowedException e) {
            assertTrue(e.getMessage().contains("sub-workflow"));
        }
    }

    // ---------- validateStrategy ----------

    @Test
    public void validateStrategy_passesWhenNull() throws Exception {
        SuspensionPolicy.validateStrategy(null);
    }

    // Note: validateStrategy for parallel rejection is exercised in Wave 1
    // integration tests with a real ParallelWorkflowStrategy instance. Wave 0
    // tests cannot instantiate WorkflowStrategy without engine machinery.

    // ---------- fixtures ----------

    private static final class DurableComponent implements CheckpointableContextComponent<String> {
        @Override public String getName() { return "durable"; }
        @Override public Class<String> getType() { return String.class; }
        @Override public String getObject() { return "value"; }
        @Override public Object checkpointState() { return "state"; }
        @Override public void restoreState(Object state, IFramework framework) { /* no-op */ }
    }

    private static final class PlainMockWriter implements StreamingLogWriter {
        @Override public void openStream() throws IOException { }
        @Override public void addEvent(LogEvent event) { }
        @Override public void close() { }
    }

    private static final class CheckpointableMockWriter implements CheckpointableStreamingLogWriter {
        @Override public void openStream() throws IOException { }
        @Override public void addEvent(LogEvent event) { }
        @Override public void close() { }
        @Override public void suspend() { }
    }
}
