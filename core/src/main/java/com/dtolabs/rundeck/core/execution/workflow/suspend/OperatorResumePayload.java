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

/**
 * {@link ResumePayload} subtype for the operator-pause feature. Carries
 * the operator who released the pause and an optional comment.
 *
 * <p>See {@code docs/specs/operator-pause.md} section 4.
 */
@JsonTypeName("operator-resume")
public final class OperatorResumePayload implements ResumePayload {
    private static final long serialVersionUID = 1L;

    private final String resumedBy;
    private final String resumedAt;
    private final String comment;

    @JsonCreator
    public OperatorResumePayload(
            @JsonProperty("resumedBy") String resumedBy,
            @JsonProperty("resumedAt") String resumedAt,
            @JsonProperty("comment") String comment
    ) {
        this.resumedBy = resumedBy;
        this.resumedAt = resumedAt;
        this.comment = comment;
    }

    @Override
    public String getType() {
        return "operator-resume";
    }

    public String getResumedBy() { return resumedBy; }
    public String getResumedAt() { return resumedAt; }
    public String getComment() { return comment; }
}
