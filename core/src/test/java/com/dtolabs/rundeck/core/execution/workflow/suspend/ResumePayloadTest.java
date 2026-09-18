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
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.NamedType;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Wave 0 exit criterion: {@link ResumePayload} polymorphic Jackson round-trip
 * with a {@code type} discriminator. Uses a local mock subtype to avoid
 * depending on {@code ConfirmResponse} or {@code OperatorResumePayload}
 * from future waves.
 */
public class ResumePayloadTest {

    private ObjectMapper mapper;

    @Before
    public void setUp() {
        mapper = new ObjectMapper();
        mapper.registerSubtypes(new NamedType(MockResumePayload.class, "mock-test"));
    }

    @Test
    public void polymorphicRoundTrip_preservesConcreteSubtype() throws Exception {
        MockResumePayload original = new MockResumePayload("hello", 42);

        String json = mapper.writeValueAsString((ResumePayload) original);
        assertNotNull(json);
        assertTrue("serialized JSON must carry type discriminator: " + json,
                json.contains("\"type\":\"mock-test\""));

        ResumePayload restored = mapper.readValue(json, ResumePayload.class);
        assertNotNull(restored);
        assertTrue("restored must be MockResumePayload, got: " + restored.getClass(),
                restored instanceof MockResumePayload);

        MockResumePayload restoredMock = (MockResumePayload) restored;
        assertEquals("hello", restoredMock.getGreeting());
        assertEquals(42, restoredMock.getCount());
        assertEquals("mock-test", restoredMock.getType());
    }

    /**
     * A trivial {@link ResumePayload} subtype for testing polymorphic
     * dispatch. Represents what a third-party plugin would contribute.
     */
    public static final class MockResumePayload implements ResumePayload {
        private static final long serialVersionUID = 1L;

        private final String greeting;
        private final int count;

        @JsonCreator
        public MockResumePayload(
                @JsonProperty("greeting") String greeting,
                @JsonProperty("count") int count
        ) {
            this.greeting = greeting;
            this.count = count;
        }

        public String getGreeting() { return greeting; }
        public int getCount() { return count; }

        @Override
        public String getType() {
            return "mock-test";
        }
    }
}
