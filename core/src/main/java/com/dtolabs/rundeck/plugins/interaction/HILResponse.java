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

import com.dtolabs.rundeck.core.execution.workflow.suspend.ResumePayload;
import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * Abstract envelope for a human-in-the-loop response. A sub-family of
 * {@link ResumePayload}: slots into the engine's existing Jackson polymorphism
 * rather than introducing a parallel mechanism.
 */
@Experimental
public interface HILResponse extends ResumePayload {

    /**
     * Primitive name. Matches the originating request's primitive (string equality).
     *
     * <p>{@code @JsonIgnore} for the same reason as {@link ResumePayload#getType()}:
     * it is a derived constant, not a serialized field, and emitting it would
     * break deserialization of implementations that do not accept it as a
     * creator property.
     */
    @JsonIgnore
    String getPrimitive();
}
