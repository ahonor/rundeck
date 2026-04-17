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
import com.fasterxml.jackson.annotation.JsonTypeName;

import java.util.Collections;
import java.util.List;

/**
 * {@link ResumePayload} subtype for the built-in confirmation step plugin.
 * Carries the confirmer's decision (approve/deny), identity, comment, and
 * timestamp. Registered as a Jackson named subtype with discriminator
 * {@code "confirmation"}.
 *
 * <p>See {@code docs/specs/confirm-workflow-step.md} section 4 for the
 * authoritative specification.
 */
@JsonTypeName("confirmation")
public final class ConfirmationPayload implements ResumePayload {
    private static final long serialVersionUID = 1L;

    private final String decision;
    private final String confirmedBy;
    private final List<String> confirmerRoles;
    private final String comment;
    private final String confirmedAt;
    private final boolean timeout;

    @JsonCreator
    public ConfirmationPayload(
            @JsonProperty("decision") String decision,
            @JsonProperty("confirmedBy") String confirmedBy,
            @JsonProperty("confirmerRoles") List<String> confirmerRoles,
            @JsonProperty("comment") String comment,
            @JsonProperty("confirmedAt") String confirmedAt,
            @JsonProperty("timeout") boolean timeout
    ) {
        this.decision = decision;
        this.confirmedBy = confirmedBy;
        this.confirmerRoles = confirmerRoles != null
                ? Collections.unmodifiableList(confirmerRoles)
                : Collections.emptyList();
        this.comment = comment;
        this.confirmedAt = confirmedAt;
        this.timeout = timeout;
    }

    @Override
    public String getType() {
        return "confirmation";
    }

    /** The decision submitted: member of the frozen decisionSet (e.g., "approve" or "deny"). */
    public String getDecision() { return decision; }

    /** User id of the confirmer. Empty string for synthetic timeout. */
    public String getConfirmedBy() { return confirmedBy; }

    /** Roles held by the confirmer at confirmation time. */
    public List<String> getConfirmerRoles() { return confirmerRoles; }

    /** Free-text comment provided by the confirmer. May be null. */
    public String getComment() { return comment; }

    /** ISO-8601 timestamp of confirmation. */
    public String getConfirmedAt() { return confirmedAt; }

    /** True if this is a synthetic payload produced by the timeout sweep. */
    public boolean isTimeout() { return timeout; }
}
