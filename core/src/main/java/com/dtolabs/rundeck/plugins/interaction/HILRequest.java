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
package com.dtolabs.rundeck.plugins.interaction;

import com.dtolabs.rundeck.core.execution.workflow.suspend.JacksonSubtypeRegistrar;
import com.dtolabs.rundeck.core.execution.workflow.suspend.SuspendRequest;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.Serializable;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Abstract envelope for a human-in-the-loop suspension request.
 *
 * <p>A {@code HILRequest} <em>produces</em> the engine's {@link SuspendRequest}
 * rather than extending it. The engine type stays final and untouched, and the
 * HIL family owns its own polymorphism so that typed per-primitive fields
 * survive a persist/resume round trip &mdash; which subclassing
 * {@code SuspendRequest} would not give, since that type carries no
 * {@code @JsonTypeInfo} and is read back as its declared type.
 *
 * <p>Concrete subtypes are resolved from the {@code "primitive"} discriminator.
 * Third-party primitives register their own subtypes through the existing
 * {@link JacksonSubtypeRegistrar}, the same hook used for
 * {@code ResumePayload} subtypes &mdash; no parallel machinery is introduced.
 */
@Experimental
@JsonTypeInfo(
        use = JsonTypeInfo.Id.NAME,
        include = JsonTypeInfo.As.PROPERTY,
        property = "primitive"
)
@JsonSubTypes({
        @JsonSubTypes.Type(value = ConfirmRequest.class, name = HILPrimitives.CONFIRM)
})
public abstract class HILRequest implements Serializable {
    private static final long serialVersionUID = 1L;

    /**
     * Reserved key under which the serialized typed request is carried in
     * {@link SuspendRequest#getMetadata()}, so the resume path can recover the
     * concrete subtype. Consumers reading suspend metadata for display should
     * ignore this key.
     */
    public static final String METADATA_KEY = "hilRequest";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String token;
    private final long timeoutMs;
    private final String reason;

    protected HILRequest(String token, long timeoutMs, String reason) {
        this.token = token;
        this.timeoutMs = timeoutMs;
        this.reason = reason;
    }

    /**
     * Primitive name. Conventionally namespaced ("vendor.primitive"). Never null
     * or empty.
     *
     * <p>{@code @JsonIgnore} prevents double-emission: the {@code @JsonTypeInfo}
     * mechanism already writes this as the {@code "primitive"} property.
     */
    @JsonIgnore
    public abstract String getPrimitive();

    /** Unique identifier for this suspension. */
    public String getToken() { return token; }

    /** Max wait in milliseconds before the engine synthesizes a timeout response. */
    public long getTimeoutMs() { return timeoutMs; }

    /** Short human-readable description of why the execution is parked. */
    public String getReason() { return reason; }

    /**
     * Build the engine-facing suspension envelope. The engine calls this and
     * passes the result to {@code StepExecutionContext.suspend(...)}; the typed
     * {@code HILRequest} is persisted alongside it for the resume path.
     *
     * @return an equivalent {@link SuspendRequest}
     */
    public final SuspendRequest toSuspendRequest() {
        Map<String, Object> metadata = new LinkedHashMap<>(suspendMetadata());
        metadata.put(METADATA_KEY, serialized());
        return SuspendRequest.builder()
                .token(token)
                .timeoutMs(timeoutMs)
                .reason(reason)
                .waitingFor(getPrimitive())
                .metadata(metadata)
                .build();
    }

    private String serialized() {
        try {
            return MAPPER.writeValueAsString(this);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "HILRequest for primitive '" + getPrimitive() + "' is not serializable", e);
        }
    }

    /**
     * Recover the typed request from the frozen suspend metadata presented on a
     * resume invocation. Resolves to the concrete subtype via the
     * {@code "primitive"} discriminator.
     *
     * @param metadata the map from {@code StepExecutionContext.getSuspendMetadata()}
     * @return the typed request, or {@code null} if this suspension was not
     *         created by an {@link InteractionPlugin}
     */
    public static HILRequest fromSuspendMetadata(Map<String, Object> metadata) {
        if (metadata == null) {
            return null;
        }
        Object raw = metadata.get(METADATA_KEY);
        if (!(raw instanceof String)) {
            return null;
        }
        try {
            return MAPPER.readValue((String) raw, HILRequest.class);
        } catch (IOException e) {
            throw new IllegalStateException("Stored HILRequest could not be read back", e);
        }
    }

    /**
     * Fields to publish into {@link SuspendRequest#getMetadata()} for
     * framework-visible consumers such as REST controllers and the paused-step
     * UI. The typed object remains the source of truth; this is a projection of
     * it. Default publishes nothing.
     *
     * @return metadata entries, never null
     */
    protected Map<String, Object> suspendMetadata() {
        return Collections.emptyMap();
    }

    /**
     * Validate that a response is well-formed for this specific request. Called
     * by the engine after deserialization and before dispatch to
     * {@link InteractionPlugin#onResponse}. A non-empty return rejects the
     * response and surfaces the messages to the caller.
     *
     * @param response the candidate response
     * @return validation error messages, empty if the response is acceptable
     */
    public List<String> validateResponse(HILResponse response) {
        return Collections.emptyList();
    }
}
