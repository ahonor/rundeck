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

/**
 * Well-known human-in-the-loop primitive names.
 *
 * <p>These constants are a convenience, not an enforcement mechanism. A plugin
 * declaring its own primitive (e.g. {@code "acme.signature"}) is a first-class
 * participant; core does not distinguish well-known names from third-party
 * names. Convention is {@code vendor.primitive} namespacing.
 */
@Experimental
public final class HILPrimitives {

    /** Approve/deny gate. Concrete types ship in core at v1. */
    public static final String CONFIRM = "rundeck.confirm";

    /** Pick one of N options. Well-known name; concrete types not shipped at v1. */
    public static final String CHOOSE = "rundeck.choose";

    /** Typed structured input (e.g. string with regex validation). */
    public static final String ASK = "rundeck.ask";

    /** Proceed/modify/abort against a proposed plan. */
    public static final String REVIEW = "rundeck.review";

    /** Signed witness, optionally counter-signed. */
    public static final String ATTEST = "rundeck.attest";

    /** Policy-override request to a higher approver pool. */
    public static final String ESCALATE = "rundeck.escalate";

    /** Order items by priority. */
    public static final String RANK = "rundeck.rank";

    private HILPrimitives() {
    }
}
