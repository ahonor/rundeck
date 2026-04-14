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
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable value type describing a suspension request. A step plugin constructs
 * one of these and passes it to {@code StepExecutionContext.suspend(request)} to
 * park the execution until the external event named by the request arrives.
 *
 * <p>Fields:
 * <ul>
 *   <li>{@code token} &mdash; unique identifier for this suspension (plugin-provided).</li>
 *   <li>{@code timeoutMs} &mdash; max wait in milliseconds before the runtime
 *       synthesizes a timeout resume.</li>
 *   <li>{@code reason} &mdash; short human-readable description of why the
 *       execution is parked.</li>
 *   <li>{@code waitingFor} &mdash; structured identifier of what is being waited on
 *       (e.g., {@code "user-confirmation"}).</li>
 *   <li>{@code metadata} &mdash; framework-visible structured data, stored in
 *       {@code Execution.suspend_metadata} and readable by consumer API
 *       controllers (e.g., {@code ApiConfirmController}). Subject to the
 *       configuration-freeze contract: plugins read from this map on resume
 *       rather than re-reading the job definition.</li>
 *   <li>{@code payload} &mdash; plugin-internal opaque data. Persisted with the
 *       checkpoint but NOT read by anything outside the plugin itself.</li>
 * </ul>
 *
 * See {@code docs/specs/workflow-suspend-resume.md} section 2.1 for the
 * authoritative specification.
 */
public final class SuspendRequest implements Serializable {
    private static final long serialVersionUID = 1L;

    private final String token;
    private final long timeoutMs;
    private final String reason;
    private final String waitingFor;
    private final Map<String, Object> metadata;
    private final Map<String, Object> payload;

    @JsonCreator
    public SuspendRequest(
            @JsonProperty("token") String token,
            @JsonProperty("timeoutMs") long timeoutMs,
            @JsonProperty("reason") String reason,
            @JsonProperty("waitingFor") String waitingFor,
            @JsonProperty("metadata") Map<String, Object> metadata,
            @JsonProperty("payload") Map<String, Object> payload
    ) {
        this.token = token;
        this.timeoutMs = timeoutMs;
        this.reason = reason;
        this.waitingFor = waitingFor;
        this.metadata = metadata == null
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new HashMap<>(metadata));
        this.payload = payload == null
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new HashMap<>(payload));
    }

    public String getToken() { return token; }
    public long getTimeoutMs() { return timeoutMs; }
    public String getReason() { return reason; }
    public String getWaitingFor() { return waitingFor; }
    public Map<String, Object> getMetadata() { return metadata; }
    public Map<String, Object> getPayload() { return payload; }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SuspendRequest)) return false;
        SuspendRequest that = (SuspendRequest) o;
        return timeoutMs == that.timeoutMs
                && Objects.equals(token, that.token)
                && Objects.equals(reason, that.reason)
                && Objects.equals(waitingFor, that.waitingFor)
                && Objects.equals(metadata, that.metadata)
                && Objects.equals(payload, that.payload);
    }

    @Override
    public int hashCode() {
        return Objects.hash(token, timeoutMs, reason, waitingFor, metadata, payload);
    }

    @Override
    public String toString() {
        return "SuspendRequest{"
                + "token='" + token + '\''
                + ", timeoutMs=" + timeoutMs
                + ", reason='" + reason + '\''
                + ", waitingFor='" + waitingFor + '\''
                + '}';
    }

    /**
     * Fluent builder for {@link SuspendRequest}. Canonical usage pattern:
     *
     * <pre>
     * return context.suspend(SuspendRequest.builder()
     *         .token(generateToken())
     *         .reason("awaiting approval")
     *         .waitingFor("user-confirmation")
     *         .timeoutMs(TimeUnit.HOURS.toMillis(24))
     *         .metadata(Map.of(
     *                 "type", "confirmation",
     *                 "message", resolvedMessage))
     *         .build());
     * </pre>
     */
    public static final class Builder {
        private String token;
        private long timeoutMs;
        private String reason;
        private String waitingFor;
        private Map<String, Object> metadata;
        private Map<String, Object> payload;

        public Builder token(String token) { this.token = token; return this; }
        public Builder timeoutMs(long timeoutMs) { this.timeoutMs = timeoutMs; return this; }
        public Builder reason(String reason) { this.reason = reason; return this; }
        public Builder waitingFor(String waitingFor) { this.waitingFor = waitingFor; return this; }
        public Builder metadata(Map<String, Object> metadata) { this.metadata = metadata; return this; }
        public Builder payload(Map<String, Object> payload) { this.payload = payload; return this; }

        public SuspendRequest build() {
            return new SuspendRequest(token, timeoutMs, reason, waitingFor, metadata, payload);
        }
    }
}
