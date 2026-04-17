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
package rundeck

/**
 * Wave 5 cycle/workflow-suspend-resume: immutable audit trail for
 * confirmation events. One row per confirmation (human or synthetic
 * timeout). Multiple rows may exist per execution if the workflow has
 * multiple confirm steps. Rows are permanent — NOT cleared on terminal
 * transition (unlike suspend-related columns on Execution per I11).
 *
 * <p>See {@code docs/specs/confirm-workflow-step.md} section 7.
 */
class ExecutionConfirmation {

    Long executionId
    String confirmedBy
    String confirmerRoles
    String decision
    String comment
    Date confirmedAt
    Boolean timeout = false
    String stepContext

    static constraints = {
        executionId(nullable: false)
        confirmedBy(nullable: true, maxSize: 255)
        confirmerRoles(nullable: true, maxSize: 1024)
        decision(nullable: true, maxSize: 64)
        comment(nullable: true, maxSize: 2048)
        confirmedAt(nullable: false)
        stepContext(nullable: false, maxSize: 64)
    }

    static mapping = {
        version false
        table 'execution_confirmation'
        comment type: 'text'
    }
}
