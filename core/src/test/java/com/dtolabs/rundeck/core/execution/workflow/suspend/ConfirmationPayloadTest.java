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

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Wave 5: {@link ConfirmationPayload} Jackson round-trip via the
 * {@link ResumePayload} polymorphic interface.
 */
public class ConfirmationPayloadTest {

    @Test
    public void polymorphicRoundTrip_viaResumePayload() throws Exception {
        ConfirmationPayload original = new ConfirmationPayload(
                "approve",
                "bob",
                Arrays.asList("sre", "admin"),
                "LGTM after CI passed",
                "2026-04-14T15:23:00Z",
                false
        );

        ObjectMapper mapper = new ObjectMapper();
        String json = mapper.writeValueAsString((ResumePayload) original);
        assertNotNull(json);
        assertTrue("JSON must carry type discriminator", json.contains("\"type\":\"confirmation\""));

        ResumePayload restored = mapper.readValue(json, ResumePayload.class);
        assertNotNull(restored);
        assertTrue("restored must be ConfirmationPayload",
                restored instanceof ConfirmationPayload);

        ConfirmationPayload cp = (ConfirmationPayload) restored;
        assertEquals("approve", cp.getDecision());
        assertEquals("bob", cp.getConfirmedBy());
        assertEquals(Arrays.asList("sre", "admin"), cp.getConfirmerRoles());
        assertEquals("LGTM after CI passed", cp.getComment());
        assertEquals("2026-04-14T15:23:00Z", cp.getConfirmedAt());
        assertFalse(cp.isTimeout());
    }

    @Test
    public void timeoutPayload_roundTrip() throws Exception {
        ConfirmationPayload original = new ConfirmationPayload(
                null, "", null, null, "2026-04-14T23:00:00Z", true
        );

        ObjectMapper mapper = new ObjectMapper();
        String json = mapper.writeValueAsString((ResumePayload) original);
        ResumePayload restored = mapper.readValue(json, ResumePayload.class);

        assertTrue(restored instanceof ConfirmationPayload);
        ConfirmationPayload cp = (ConfirmationPayload) restored;
        assertTrue(cp.isTimeout());
    }
}
