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

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Wave 0 exit criterion: {@link SuspendRequest} Jackson round-trip.
 */
public class SuspendRequestTest {

    @Test
    public void builder_constructsExpectedValue() {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("type", "confirmation");
        metadata.put("message", "approve deploy?");

        SuspendRequest req = SuspendRequest.builder()
                .token("abc-123")
                .timeoutMs(60_000L)
                .reason("awaiting approval")
                .waitingFor("user-confirmation")
                .metadata(metadata)
                .build();

        assertEquals("abc-123", req.getToken());
        assertEquals(60_000L, req.getTimeoutMs());
        assertEquals("awaiting approval", req.getReason());
        assertEquals("user-confirmation", req.getWaitingFor());
        assertEquals("confirmation", req.getMetadata().get("type"));
        assertEquals("approve deploy?", req.getMetadata().get("message"));
    }

    @Test
    public void metadata_isImmutable() {
        Map<String, Object> original = new HashMap<>();
        original.put("key", "value");

        SuspendRequest req = SuspendRequest.builder().metadata(original).build();

        original.put("injected", "after-build");
        assertEquals("value", req.getMetadata().get("key"));
        assertTrue("metadata must not reflect post-build mutation",
                !req.getMetadata().containsKey("injected"));
    }

    @Test
    public void jacksonRoundtrip_preservesAllFields() throws Exception {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("type", "confirmation");
        metadata.put("decisionSet", "approve,deny");

        Map<String, Object> payload = new HashMap<>();
        payload.put("internal-state", "whatever");

        SuspendRequest original = SuspendRequest.builder()
                .token("roundtrip-token")
                .timeoutMs(3_600_000L)
                .reason("test")
                .waitingFor("user-confirmation:sre")
                .metadata(metadata)
                .payload(payload)
                .build();

        ObjectMapper mapper = new ObjectMapper();
        String json = mapper.writeValueAsString(original);
        assertNotNull(json);

        SuspendRequest restored = mapper.readValue(json, SuspendRequest.class);
        assertEquals(original, restored);
    }

    @Test
    public void nullMetadataAndPayload_defaultToEmptyMaps() {
        SuspendRequest req = SuspendRequest.builder()
                .token("bare")
                .timeoutMs(1000L)
                .build();

        assertNotNull(req.getMetadata());
        assertNotNull(req.getPayload());
        assertTrue(req.getMetadata().isEmpty());
        assertTrue(req.getPayload().isEmpty());
    }

    @Test
    public void equalsAndHashCode_areConsistent() {
        SuspendRequest a = SuspendRequest.builder().token("t").timeoutMs(1).build();
        SuspendRequest b = SuspendRequest.builder().token("t").timeoutMs(1).build();
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }
}
