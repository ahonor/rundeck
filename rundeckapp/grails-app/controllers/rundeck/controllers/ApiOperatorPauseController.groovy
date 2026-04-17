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
import com.dtolabs.rundeck.core.execution.workflow.suspend.OperatorResumePayload
import com.fasterxml.jackson.databind.ObjectMapper
import groovy.util.logging.Slf4j
import org.rundeck.core.auth.AuthConstants
import org.rundeck.app.authorization.AppAuthContextProcessor
import rundeck.Execution
import rundeck.services.ExecutionResumeService
import rundeck.services.ExecutionService

/**
 * Wave 6 cycle/workflow-suspend-resume: REST API controller for the
 * operator-initiated pause/resume feature. Provides endpoints for
 * pausing a running execution at the next step boundary, resuming a
 * paused execution, and querying pause status.
 *
 * <p>See {@code docs/specs/operator-pause.md} section 6 for the
 * authoritative endpoint specifications.
 */
@Slf4j
class ApiOperatorPauseController {

    ExecutionService executionService
    ExecutionResumeService executionResumeService
    AppAuthContextProcessor rundeckAuthContextProcessor

    private final ObjectMapper objectMapper = new ObjectMapper()

    /**
     * POST /api/{v}/execution/{id}/pause
     *
     * Request a pause at the next step boundary.
     * Request body (optional): {"reason": "maintenance window"}
     */
    def pause() {
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

        // Must be running
        if (execution.status != ExecutionService.EXECUTION_RUNNING && execution.status != 'running') {
            response.status = 409
            render([error: "execution is not running (status: ${execution.status})"] as grails.converters.JSON)
            return
        }

        // ACL enforcement
        AuthContext authContext = rundeckAuthContextProcessor.getAuthContextForSubjectAndProject(
                session.subject, execution.project)
        if (!rundeckAuthContextProcessor.authorizeProjectExecutionAll(
                authContext, execution, [AuthConstants.ACTION_PAUSE])) {
            response.status = 403
            render([error: 'not authorized to pause this execution'] as grails.converters.JSON)
            return
        }

        def body = request.JSON
        String reason = body?.reason ?: ''

        // Set pause_requested flag in the DB. The engine's StepCallable
        // reads this flag (via the pauseCheckSupplier on the context) before
        // each step invocation and synthesizes a suspension if true.
        Execution.withNewTransaction {
            Execution e = Execution.get(execId)
            if (e) {
                e.pauseRequested = true
                e.save(flush: true)
            }
        }
        log.info("Pause requested for execution ${execId} by ${request.remoteUser}: ${reason}")

        response.status = 200
        render([
                paused: true,
                effectiveAfter: "next step boundary"
        ] as grails.converters.JSON)
    }

    /**
     * POST /api/{v}/execution/{id}/resume
     *
     * Resume an operator-paused execution.
     * Request body (optional): {"comment": "maintenance complete"}
     */
    def resume() {
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

        if (execution.status != ExecutionService.EXECUTION_WAITING) {
            response.status = 409
            render([error: "execution is not waiting (status: ${execution.status})"] as grails.converters.JSON)
            return
        }

        // Check suspend type is operator-pause
        Map metadata = [:]
        if (execution.suspendMetadata) {
            try {
                metadata = objectMapper.readValue(execution.suspendMetadata, Map)
            } catch (Exception ignored) {}
        }
        if (metadata.get('type') != 'operator-pause') {
            response.status = 409
            render([error: "execution is waiting for a different event type (type: ${metadata.get('type')}); use the appropriate endpoint"] as grails.converters.JSON)
            return
        }

        // ACL enforcement
        AuthContext authContext = rundeckAuthContextProcessor.getAuthContextForSubjectAndProject(
                session.subject, execution.project)
        if (!rundeckAuthContextProcessor.authorizeProjectExecutionAll(
                authContext, execution, [AuthConstants.ACTION_PAUSE])) {
            response.status = 403
            render([error: 'not authorized to resume this execution'] as grails.converters.JSON)
            return
        }

        def body = request.JSON
        String comment = body?.comment ?: ''

        def payload = new OperatorResumePayload(
                request.remoteUser ?: 'unknown',
                new Date().toInstant().toString(),
                comment
        )

        // Clear the pause_requested flag + mark resume ready
        Execution.withNewTransaction {
            Execution e = Execution.get(execId)
            if (e) {
                e.pauseRequested = false
                e.save(flush: true)
            }
        }

        boolean marked = executionResumeService.markResumeReady(execution.id, payload)
        if (!marked) {
            response.status = 409
            render([error: 'execution is no longer waiting'] as grails.converters.JSON)
            return
        }

        log.info("Resume requested for execution ${execId} by ${request.remoteUser}: ${comment}")

        response.status = 200
        render([
                resumed: true,
                resumedBy: request.remoteUser ?: 'unknown'
        ] as grails.converters.JSON)
    }

    /**
     * GET /api/{v}/execution/{id}/pause/status
     *
     * Returns the current pause state.
     */
    def pauseStatus() {
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

        boolean isOperatorPause = execution.status == ExecutionService.EXECUTION_WAITING &&
                metadata.get('type') == 'operator-pause'

        // Check ACL
        boolean callerCanPause = false
        try {
            AuthContext authContext = rundeckAuthContextProcessor.getAuthContextForSubjectAndProject(
                    session.subject, execution.project)
            callerCanPause = rundeckAuthContextProcessor.authorizeProjectExecutionAll(
                    authContext, execution, [AuthConstants.ACTION_PAUSE])
        } catch (Exception ignored) {}

        render([
                pauseRequested: execution.pauseRequested,
                currentStatus: execution.status,
                suspendType: isOperatorPause ? 'operator-pause' : metadata.get('type'),
                requestedBy: metadata.get('requestedBy'),
                requestedAt: metadata.get('requestedAt'),
                reason: metadata.get('reason'),
                waitStartedAt: execution.waitStartedAt?.toInstant()?.toString(),
                callerCanPause: callerCanPause
        ] as grails.converters.JSON)
    }
}
