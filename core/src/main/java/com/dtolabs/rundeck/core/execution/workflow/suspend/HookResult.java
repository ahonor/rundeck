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

import java.util.Objects;

/**
 * Result returned by a {@link PreNextStepHook} evaluation. Either
 * {@link #PROCEED} (no intervention) or a suspension synthesized via
 * {@link #synthesizeSuspension(SuspendRequest)}.
 */
public final class HookResult {

    /** Singleton: continue to the next step without intervention. */
    public static final HookResult PROCEED = new HookResult(null);

    private final SuspendRequest suspendRequest;

    private HookResult(SuspendRequest suspendRequest) {
        this.suspendRequest = suspendRequest;
    }

    /**
     * Create a result that requests the engine to synthesize a suspension
     * at this step boundary.
     */
    public static HookResult synthesizeSuspension(SuspendRequest request) {
        return new HookResult(Objects.requireNonNull(request, "suspendRequest"));
    }

    /** Whether this result requests a suspension. */
    public boolean isSuspend() {
        return suspendRequest != null;
    }

    /** The suspension request, or null if {@link #isSuspend()} is false. */
    public SuspendRequest getSuspendRequest() {
        return suspendRequest;
    }
}
