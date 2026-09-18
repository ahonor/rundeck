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
package com.dtolabs.rundeck.plugins.interaction

import com.dtolabs.rundeck.core.execution.workflow.suspend.SuspendRequest
import com.fasterxml.jackson.databind.ObjectMapper
import spock.lang.Specification

/**
 * The HIL request family owns its own Jackson polymorphism, so a concrete
 * per-primitive request survives a persist/resume round trip with its typed
 * fields intact.
 */
class HILRequestRoundTripSpec extends Specification {

    static ConfirmRequest sample() {
        ConfirmRequest.builder()
                .token("tok-1")
                .timeoutMs(86_400_000L)
                .reason("awaiting approval")
                .message("Deploy release 6.0 to production?")
                .criticality("high")
                .source("job")
                .timeoutAction("deny")
                .decisionSet(["approve", "deny"])
                .requiredConfirmerRoles(["release_manager"])
                .build()
    }

    def "concrete request round-trips through the abstract envelope with typed fields intact"() {
        given:
        def mapper = new ObjectMapper()
        def original = sample()

        when:
        def json = mapper.writeValueAsString(original)
        def revived = mapper.readValue(json, HILRequest)

        then: "the concrete subtype is resolved, not the abstract envelope"
        revived instanceof ConfirmRequest

        and: "per-primitive fields survive"
        revived.message == "Deploy release 6.0 to production?"
        revived.criticality == "high"
        revived.source == "job"
        revived.decisionSet == ["approve", "deny"]
        revived.requiredConfirmerRoles == ["release_manager"]

        and: "envelope fields survive"
        revived.token == "tok-1"
        revived.timeoutMs == 86_400_000L
        revived.reason == "awaiting approval"
        revived.primitive == HILPrimitives.CONFIRM

        and:
        revived == original
    }

    def "the discriminator is written once, under the primitive name"() {
        when:
        def json = new ObjectMapper().readValue(
                new ObjectMapper().writeValueAsString(sample()), Map)

        then:
        json.primitive == HILPrimitives.CONFIRM
        json.count { k, v -> k == "primitive" } == 1
    }

    def "validateResponse enforces the decision set after a round trip"() {
        given:
        def mapper = new ObjectMapper()
        def revived = mapper.readValue(mapper.writeValueAsString(sample()), HILRequest)

        expect: "a decision in the set passes"
        revived.validateResponse(
                new ConfirmResponse("approve", "alice", ["release_manager"], "lgtm", "2026-09-17T10:00:00Z", false)
        ).isEmpty()

        and: "one outside it is rejected"
        !revived.validateResponse(
                new ConfirmResponse("maybe", "alice", ["release_manager"], null, "2026-09-17T10:00:00Z", false)
        ).isEmpty()

        and: "a synthetic timeout is exempt"
        revived.validateResponse(
                new ConfirmResponse(null, null, null, null, "2026-09-17T10:00:00Z", true)
        ).isEmpty()
    }

def "the typed request survives suspend and is recovered from frozen metadata on resume"() {
        given: "a request projected into an engine suspend envelope"
        SuspendRequest envelope = sample().toSuspendRequest()

        when: "the resume path recovers the typed request from frozen metadata"
        def recovered = HILRequest.fromSuspendMetadata(envelope.metadata)

        then: "the concrete subtype comes back, not the abstract envelope"
        recovered instanceof ConfirmRequest

        and: "every per-primitive field survived the trip"
        recovered.message == "Deploy release 6.0 to production?"
        recovered.decisionSet == ["approve", "deny"]
        recovered.requiredConfirmerRoles == ["release_manager"]
        recovered.timeoutAction == "deny"

        and: "so validation is callable after resume, which is the point"
        recovered.validateResponse(
                new ConfirmResponse("maybe", "alice", [], null, "2026-09-18T10:00:00Z", false)
        ).size() == 1
    }

    def "a suspension not created by an InteractionPlugin recovers as null"() {
        expect:
        HILRequest.fromSuspendMetadata(null) == null
        HILRequest.fromSuspendMetadata([:]) == null
        HILRequest.fromSuspendMetadata([type: "operator-pause"]) == null
    }

    def "toSuspendRequest projects the engine-facing envelope"() {
        when:
        SuspendRequest sr = sample().toSuspendRequest()

        then:
        sr.token == "tok-1"
        sr.timeoutMs == 86_400_000L
        sr.reason == "awaiting approval"
        sr.waitingFor == HILPrimitives.CONFIRM

        and: "framework-visible consumers see the projection"
        sr.metadata.message == "Deploy release 6.0 to production?"
        sr.metadata.decisionSet == ["approve", "deny"]
        sr.metadata.requiredConfirmerRoles == ["release_manager"]
    }
}
