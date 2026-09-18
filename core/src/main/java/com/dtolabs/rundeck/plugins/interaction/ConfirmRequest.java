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

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Request for the {@link HILPrimitives#CONFIRM} primitive: an approve/deny gate.
 */
@Experimental
public final class ConfirmRequest extends HILRequest {
    private static final long serialVersionUID = 1L;

    private final String message;
    private final String criticality;
    private final String source;
    private final String timeoutAction;
    private final List<String> decisionSet;
    private final List<String> requiredConfirmerRoles;

    @JsonCreator
    ConfirmRequest(
            @JsonProperty("token") String token,
            @JsonProperty("timeoutMs") long timeoutMs,
            @JsonProperty("reason") String reason,
            @JsonProperty("message") String message,
            @JsonProperty("criticality") String criticality,
            @JsonProperty("source") String source,
            @JsonProperty("timeoutAction") String timeoutAction,
            @JsonProperty("decisionSet") List<String> decisionSet,
            @JsonProperty("requiredConfirmerRoles") List<String> requiredConfirmerRoles
    ) {
        super(token, timeoutMs, reason);
        this.message = message;
        this.criticality = criticality;
        this.source = source;
        this.timeoutAction = timeoutAction;
        this.decisionSet = immutable(decisionSet);
        this.requiredConfirmerRoles = immutable(requiredConfirmerRoles);
    }

    private static List<String> immutable(List<String> in) {
        return in == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(in));
    }

    @Override
    public String getPrimitive() {
        return HILPrimitives.CONFIRM;
    }

    @Override
    protected Map<String, Object> suspendMetadata() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("primitive", HILPrimitives.CONFIRM);
        m.put("message", message);
        m.put("criticality", criticality);
        m.put("source", source);
        m.put("timeoutAction", timeoutAction);
        m.put("decisionSet", decisionSet);
        m.put("requiredConfirmerRoles", requiredConfirmerRoles);
        return Collections.unmodifiableMap(m);
    }

    @Override
    public List<String> validateResponse(HILResponse response) {
        if (!(response instanceof ConfirmResponse)) {
            return Collections.singletonList("response is not a ConfirmResponse");
        }
        ConfirmResponse cr = (ConfirmResponse) response;
        if (cr.isTimeout()) {
            return Collections.emptyList();
        }
        if (!decisionSet.contains(cr.getDecision())) {
            return Collections.singletonList("decision '" + cr.getDecision()
                    + "' is not a member of decisionSet " + decisionSet);
        }
        return Collections.emptyList();
    }

    public String getMessage() { return message; }

    public String getCriticality() { return criticality; }

    public String getSource() { return source; }

    /** Policy applied when the suspension times out: "deny", "approve" or "fail". */
    public String getTimeoutAction() { return timeoutAction; }

    public List<String> getDecisionSet() { return decisionSet; }

    public List<String> getRequiredConfirmerRoles() { return requiredConfirmerRoles; }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ConfirmRequest)) return false;
        ConfirmRequest that = (ConfirmRequest) o;
        return getTimeoutMs() == that.getTimeoutMs()
                && Objects.equals(getToken(), that.getToken())
                && Objects.equals(getReason(), that.getReason())
                && Objects.equals(message, that.message)
                && Objects.equals(criticality, that.criticality)
                && Objects.equals(source, that.source)
                && Objects.equals(timeoutAction, that.timeoutAction)
                && Objects.equals(decisionSet, that.decisionSet)
                && Objects.equals(requiredConfirmerRoles, that.requiredConfirmerRoles);
    }

    @Override
    public int hashCode() {
        return Objects.hash(getToken(), getTimeoutMs(), getReason(), message,
                criticality, source, timeoutAction, decisionSet, requiredConfirmerRoles);
    }

    @Override
    public String toString() {
        return "ConfirmRequest{token='" + getToken() + "', criticality='" + criticality
                + "', decisionSet=" + decisionSet + '}';
    }

    /** Fluent builder for {@link ConfirmRequest}. */
    public static final class Builder {
        private String token;
        private long timeoutMs;
        private String reason;
        private String message;
        private String criticality;
        private String source;
        private String timeoutAction;
        private List<String> decisionSet;
        private List<String> requiredConfirmerRoles;

        public Builder token(String v) { this.token = v; return this; }

        public Builder timeoutMs(long v) { this.timeoutMs = v; return this; }

        public Builder reason(String v) { this.reason = v; return this; }

        public Builder message(String v) { this.message = v; return this; }

        public Builder criticality(String v) { this.criticality = v; return this; }

        public Builder source(String v) { this.source = v; return this; }

        public Builder timeoutAction(String v) { this.timeoutAction = v; return this; }

        public Builder decisionSet(List<String> v) { this.decisionSet = v; return this; }

        public Builder requiredConfirmerRoles(List<String> v) { this.requiredConfirmerRoles = v; return this; }

        public ConfirmRequest build() {
            return new ConfirmRequest(token, timeoutMs, reason, message, criticality,
                    source, timeoutAction, decisionSet, requiredConfirmerRoles);
        }
    }
}
