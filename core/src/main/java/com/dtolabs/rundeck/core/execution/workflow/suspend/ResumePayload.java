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

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.io.Serializable;

/**
 * Polymorphic marker for data delivered when a suspended execution is resumed.
 *
 * <p>Concrete subtypes are defined by consumers of the suspend primitive:
 * <ul>
 *   <li>{@code ConfirmationPayload} &mdash; shipped with the built-in confirm
 *       workflow step plugin. Carries approver identity, decision, and comment.</li>
 *   <li>{@code OperatorResumePayload} &mdash; shipped with the operator-pause
 *       feature. Carries the operator who released the pause.</li>
 * </ul>
 *
 * <p>Jackson deserialization uses the {@link #getType()} discriminator to
 * resolve the concrete subtype from the JSON {@code "type"} property.
 * Third-party suspendable plugins register additional subtypes via a
 * Spring-bean-discovered {@link JacksonSubtypeRegistrar}.
 *
 * <p>See spec section 2.1 and section 12 decision 16 in
 * {@code docs/specs/workflow-suspend-resume.md}.
 */
@JsonTypeInfo(
        use = JsonTypeInfo.Id.NAME,
        include = JsonTypeInfo.As.PROPERTY,
        property = "type"
)
public interface ResumePayload extends Serializable {
    /**
     * Discriminator used by Jackson polymorphic deserialization to resolve
     * the concrete subtype. Must be unique across registered subtypes.
     *
     * <p>Annotated with {@code @JsonIgnore} to prevent double-emission: the
     * {@code @JsonTypeInfo} mechanism at the interface level already writes
     * the discriminator as a {@code "type"} property during serialization.
     * Without {@code @JsonIgnore}, the bean's {@code getType()} getter would
     * also emit the field, producing a conflict on deserialization.
     *
     * @return the type name registered for this subtype
     */
    @JsonIgnore
    String getType();
}
