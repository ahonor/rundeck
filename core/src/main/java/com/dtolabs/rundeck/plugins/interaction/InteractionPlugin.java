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

import com.dtolabs.rundeck.core.execution.workflow.steps.StepException;
import com.dtolabs.rundeck.plugins.step.PluginStepContext;

import java.util.Map;

/**
 * SPI for workflow steps that solicit structured input from a human
 * mid-execution. Registered under the {@code "Interaction"} plugin service.
 *
 * <p>Implementations handle exactly one primitive. Engine concerns &mdash;
 * persistence, REST, ACL, audit, the resume worker &mdash; do not leak into
 * this contract.
 */
@Experimental
public interface InteractionPlugin {

    /**
     * Declare the primitive name this plugin handles. Plugins handle exactly one
     * primitive; use cases bundling several ship several plugin classes, typically
     * in the same jar. Any non-empty namespaced string is permitted &mdash; core
     * does not gate the set of names.
     *
     * @return the primitive name, e.g. {@link HILPrimitives#CONFIRM}
     */
    String supportedPrimitive();

    /**
     * Called when the workflow reaches this step. Returns a typed request
     * envelope; the plugin does not call {@code suspend()} itself. The returned
     * request's primitive must equal {@link #supportedPrimitive()}.
     *
     * @param context       step context, with resolved configuration
     * @param configuration resolved plugin property values
     * @return the suspension request to hand to the engine
     */
    HILRequest prepareRequest(PluginStepContext context, Map<String, Object> configuration);

    /**
     * Called when a structured response arrives via the resume path, including
     * the synthetic response the engine generates on timeout. Implementations
     * publish output context and log detail here, and throw to fail the step.
     *
     * @param context  step context reflecting configuration frozen at suspend time
     * @param response the response, whose primitive matches the request's
     * @throws StepException on a negative outcome (denied, timed out, plugin error)
     */
    void onResponse(PluginStepContext context, HILResponse response) throws StepException;
}
