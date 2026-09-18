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
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonTypeName;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Response for the {@link HILPrimitives#CONFIRM} primitive.
 *
 * <p>Implementations of {@code rundeck.confirm} publish {@code decision},
 * {@code confirmedBy}, {@code confirmerRoles}, {@code comment},
 * {@code confirmedAt} and {@code timeout} to the step output context, so that
 * workflows authored against those keys work regardless of which confirm
 * plugin is installed.
 */
@Experimental
@JsonTypeName("confirmation")
public final class ConfirmResponse implements HILResponse {
    private static final long serialVersionUID = 1L;

    private final String decision;
    private final String confirmedBy;
    private final List<String> confirmerRoles;
    private final String comment;
    private final String confirmedAt;
    private final boolean timeout;

    @JsonCreator
    public ConfirmResponse(
            @JsonProperty("decision") String decision,
            @JsonProperty("confirmedBy") String confirmedBy,
            @JsonProperty("confirmerRoles") List<String> confirmerRoles,
            @JsonProperty("comment") String comment,
            @JsonProperty("confirmedAt") String confirmedAt,
            @JsonProperty("timeout") boolean timeout
    ) {
        this.decision = decision;
        this.confirmedBy = confirmedBy;
        this.confirmerRoles = confirmerRoles == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new java.util.ArrayList<>(confirmerRoles));
        this.comment = comment;
        this.confirmedAt = confirmedAt;
        this.timeout = timeout;
    }

    @JsonIgnore
    @Override
    public String getPrimitive() {
        return HILPrimitives.CONFIRM;
    }

    @Override
    public String getType() {
        return "confirmation";
    }

    public String getDecision() { return decision; }

    public String getConfirmedBy() { return confirmedBy; }

    public List<String> getConfirmerRoles() { return confirmerRoles; }

    public String getComment() { return comment; }

    public String getConfirmedAt() { return confirmedAt; }

    public boolean isTimeout() { return timeout; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ConfirmResponse)) return false;
        ConfirmResponse that = (ConfirmResponse) o;
        return timeout == that.timeout
                && Objects.equals(decision, that.decision)
                && Objects.equals(confirmedBy, that.confirmedBy)
                && Objects.equals(confirmerRoles, that.confirmerRoles)
                && Objects.equals(comment, that.comment)
                && Objects.equals(confirmedAt, that.confirmedAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(decision, confirmedBy, confirmerRoles, comment, confirmedAt, timeout);
    }

    @Override
    public String toString() {
        return "ConfirmResponse{decision='" + decision + "', confirmedBy='" + confirmedBy
                + "', timeout=" + timeout + '}';
    }
}
