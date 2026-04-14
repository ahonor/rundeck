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

/**
 * Registration hook for plugin modules that introduce new {@link ResumePayload}
 * subtypes. Implementations are discovered as Spring beans at engine construction
 * time; the suspend-primitive's {@code ObjectMapper} factory calls
 * {@link #registerSubtypes(ObjectMapper)} on each registered bean during init.
 *
 * <p>Rundeck's plugin classloader model isolates plugins in child
 * {@code URLClassLoader}s. Jackson's built-in {@code findAndRegisterModules()}
 * uses the thread context classloader which cannot resolve plugin classes;
 * this interface sidesteps the issue by registering subtypes via Spring bean
 * wiring, which happens in the plugin's own classloader context. See spec
 * section 14 open question 5 and {@code docs/specs/workflow-suspend-resume.md}
 * section 12 decision 16.
 *
 * <p>Canonical plugin-side pattern (using Spring {@code @Bean}):
 *
 * <pre>
 * &#64;Bean
 * public JacksonSubtypeRegistrar confirmationPayloadRegistrar() {
 *     return mapper -&gt; mapper.registerSubtypes(
 *             new com.fasterxml.jackson.databind.jsontype.NamedType(
 *                     ConfirmationPayload.class, "confirmation"));
 * }
 * </pre>
 *
 * <p>Fallback: if Spring bean discovery is not feasible in a particular
 * deployment, the core can fall back to a hardcoded list of built-in subtypes
 * ({@code ConfirmationPayload}, {@code OperatorResumePayload}). Third-party
 * suspendable plugins that need additional subtypes would then require a core
 * code change.
 */
public interface JacksonSubtypeRegistrar {
    /**
     * Register one or more polymorphic subtypes of {@link ResumePayload} on
     * the provided {@link ObjectMapper}. Called once at core initialization
     * for each registered bean. Implementations should call
     * {@code mapper.registerSubtypes(...)} with {@code NamedType} entries
     * using the same string returned by each subtype's {@code getType()}.
     *
     * @param mapper the suspend-primitive's shared {@link ObjectMapper}
     */
    void registerSubtypes(ObjectMapper mapper);
}
