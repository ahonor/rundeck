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
package rundeck.controllers

import com.dtolabs.rundeck.core.authorization.AuthContext
import com.dtolabs.rundeck.plugins.interaction.ConfirmResponse
import com.fasterxml.jackson.databind.ObjectMapper
import groovy.util.logging.Slf4j
import org.rundeck.core.auth.AuthConstants
import org.rundeck.app.authorization.AppAuthContextProcessor
import rundeck.Execution
import rundeck.ExecutionConfirmation
import rundeck.services.ExecutionResumeService
import rundeck.services.ExecutionService

/**
 * Wave 5 cycle/workflow-suspend-resume: REST API controller for the
 * built-in confirmation step plugin. Provides endpoints for submitting
 * a confirmation decision (approve/deny), querying confirmation status,
 * and listing confirmation audit entries.
 *
 * <p>See {@code docs/specs/confirm-workflow-step.md} section 8 for the
 * authoritative endpoint specifications.
 *
 * <p>Design principle: API-first, UI-thin-wrapper. Every capability
 * accessible through the UI is accessible through this API. No
 * UI-exclusive capabilities.
 */
@Slf4j
class ApiConfirmController {

    ExecutionService executionService
    ExecutionResumeService executionResumeService
    AppAuthContextProcessor rundeckAuthContextProcessor

    private final ObjectMapper objectMapper = new ObjectMapper()

    /**
     * POST /api/{v}/execution/{id}/confirm
     *
     * Submit a confirmation decision for a waiting execution.
     *
     * Request body: {"decision": "approve", "comment": "LGTM"}
     * Authorization: 'confirm' ACL action on execution resource.
     */
    def confirm() {
        def execId = params.long('id')
        if (!execId) {
            response.status = 400
            render([error: 'missing execution id'] as grails.converters.JSON)
            return
        }

        def execution = Execution.get(execId)
        if (!execution) {
            response.status = 404
            render([error: "execution ${execId} not found"] as grails.converters.JSON)
            return
        }

        // Check status is waiting
        if (execution.status != ExecutionService.EXECUTION_WAITING) {
            response.status = 409
            render([error: "execution is not pending confirmation (status: ${execution.status})"] as grails.converters.JSON)
            return
        }

        // Check suspend type is confirmation
        Map metadata = [:]
        if (execution.suspendMetadata) {
            try {
                metadata = objectMapper.readValue(execution.suspendMetadata, Map)
            } catch (Exception ignored) {}
        }
        if (metadata.get('type') != 'confirmation') {
            response.status = 409
            render([error: "execution is waiting for a different event type (type: ${metadata.get('type')})"] as grails.converters.JSON)
            return
        }

        // ACL enforcement: caller must have 'confirm' action on the execution.
        AuthContext authContext = rundeckAuthContextProcessor.getAuthContextForSubjectAndProject(
                session.subject, execution.project)
        if (!rundeckAuthContextProcessor.authorizeProjectExecutionAll(
                authContext, execution, [AuthConstants.ACTION_CONFIRM])) {
            response.status = 403
            render([error: 'not authorized to confirm this execution'] as grails.converters.JSON)
            return
        }

        // Parse request body
        def body = request.JSON
        String decision = body?.decision
        String comment = body?.comment ?: ''

        // Validate decision against frozen decisionSet
        List decisionSet = (metadata.get('decisionSet') as List) ?: ['approve', 'deny']
        if (!decision || !decisionSet.contains(decision)) {
            response.status = 400
            render([error: "invalid decision '${decision}'; valid: ${decisionSet}"] as grails.converters.JSON)
            return
        }

        // Build ConfirmResponse
        def now = new Date()
        def payload = new ConfirmResponse(
                decision,
                request.remoteUser ?: 'unknown',
                [],  // confirmerRoles: populated from auth context in full wiring
                comment,
                now.toInstant().toString(),
                false  // not a timeout
        )

        // Insert audit trail row
        def confirmation = new ExecutionConfirmation(
                executionId: execution.id,
                confirmedBy: request.remoteUser ?: 'unknown',
                confirmerRoles: '',
                decision: decision,
                comment: comment,
                confirmedAt: now,
                timeout: false,
                stepContext: metadata.get('stepContext') ?: String.valueOf(execution.checkpointData ? 'unknown' : '1')
        )
        confirmation.save(flush: true)

        // Mark resume ready
        boolean marked = executionResumeService.markResumeReady(execution.id, payload)
        if (!marked) {
            response.status = 409
            render([error: 'execution is no longer waiting (state changed concurrently)'] as grails.converters.JSON)
            return
        }

        response.status = 200
        render([
                resumeReady: true,
                confirmationId: confirmation.id,
                decision: decision
        ] as grails.converters.JSON)
    }

    /**
     * GET /api/{v}/execution/{id}/confirm/status
     *
     * Returns the current confirmation state for a waiting execution.
     */
    def confirmStatus() {
        def execId = params.long('id')
        if (!execId) {
            response.status = 400
            render([error: 'missing execution id'] as grails.converters.JSON)
            return
        }

        def execution = Execution.get(execId)
        if (!execution) {
            response.status = 404
            render([error: "execution ${execId} not found"] as grails.converters.JSON)
            return
        }

        Map metadata = [:]
        if (execution.suspendMetadata) {
            try {
                metadata = objectMapper.readValue(execution.suspendMetadata, Map)
            } catch (Exception ignored) {}
        }

        boolean isWaitingConfirmation = execution.status == ExecutionService.EXECUTION_WAITING &&
                metadata.get('type') == 'confirmation'

        // Check if caller has confirm ACL for this execution
        boolean callerCanConfirm = false
        try {
            AuthContext authContext = rundeckAuthContextProcessor.getAuthContextForSubjectAndProject(
                    session.subject, execution.project)
            callerCanConfirm = rundeckAuthContextProcessor.authorizeProjectExecutionAll(
                    authContext, execution, [AuthConstants.ACTION_CONFIRM])
        } catch (Exception ignored) {
            // If auth check fails, default to false
        }

        render([
                waiting: isWaitingConfirmation,
                currentStatus: execution.status,
                source: metadata.get('source'),
                message: metadata.get('message'),
                criticality: metadata.get('criticality'),
                decisionSet: metadata.get('decisionSet'),
                requiredConfirmerRoles: metadata.get('requiredConfirmerRoles'),
                waitStartedAt: execution.waitStartedAt?.toInstant()?.toString(),
                waitTimeoutAt: execution.waitTimeoutAt?.toInstant()?.toString(),
                callerCanConfirm: callerCanConfirm
        ] as grails.converters.JSON)
    }

    /**
     * GET /api/{v}/execution/{id}/confirmations
     *
     * Returns the list of confirmation audit entries for an execution.
     */
    def confirmations() {
        def execId = params.long('id')
        if (!execId) {
            response.status = 400
            render([error: 'missing execution id'] as grails.converters.JSON)
            return
        }

        def entries = ExecutionConfirmation.findAllByExecutionId(execId, [sort: 'confirmedAt', order: 'asc'])

        render(entries.collect { ExecutionConfirmation c ->
            [
                    id: c.id,
                    executionId: c.executionId,
                    confirmedBy: c.confirmedBy,
                    decision: c.decision,
                    comment: c.comment,
                    confirmedAt: c.confirmedAt?.toInstant()?.toString(),
                    timeout: c.timeout,
                    stepContext: c.stepContext
            ]
        } as grails.converters.JSON)
    }
}
