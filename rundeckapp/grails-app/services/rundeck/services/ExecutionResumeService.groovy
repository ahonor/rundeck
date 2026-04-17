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
package rundeck.services

import com.dtolabs.rundeck.core.execution.workflow.suspend.ResumePayload
import com.fasterxml.jackson.databind.ObjectMapper
import groovy.util.logging.Slf4j
import org.springframework.scheduling.annotation.Scheduled
import rundeck.Execution

/**
 * Wave 4 cycle/workflow-suspend-resume: polling worker that resumes
 * waiting executions. Queries for executions with
 * {@code status='waiting' AND resume_ready=true AND serverNodeUUID IS NULL},
 * attempts an atomic DB claim, and on success rehydrates the execution
 * context, reopens the log writer in resume mode, spawns a fresh
 * {@code WorkflowExecutionServiceThread}, and re-enters the engine.
 *
 * <p>Also provides:
 * <ul>
 *   <li>{@link #markResumeReady(Long, ResumePayload)} — API for consumer
 *       controllers (e.g., ApiConfirmController) to deliver resume events.</li>
 *   <li>{@link #scheduledTimeoutSweep()} — finds expired waiting
 *       executions and delivers synthetic timeout payloads.</li>
 *   <li>{@link #findWaitingExecutions(String)} — operational visibility.</li>
 * </ul>
 *
 * <p>See spec §8.1 and §9.3 in
 * {@code docs/specs/workflow-suspend-resume.md} and cycle manifest Wave 4.
 */
@Slf4j
class ExecutionResumeService {
    static transactional = false

    ExecutionService executionService
    ExecutionUtilService executionUtilService
    LogFileStorageService logFileStorageService
    FrameworkService frameworkService
    LoggingService loggingService

    private final ObjectMapper objectMapper = new ObjectMapper()

    /**
     * Polling worker. Tick interval from config property
     * {@code rundeck.execution.resume.pollIntervalMs} (default 2000ms).
     *
     * <p>For each eligible row (ordered by wait_started_at ASC for
     * fairness), attempts an atomic claim via UPDATE. On successful claim
     * (1 affected row), calls {@link #resumeExecution(Execution)}. On
     * zero affected rows (race lost), skips.
     */
    // Note: @Scheduled requires Spring scheduling to be enabled in the
    // Grails application context. If it's not enabled, this method is a
    // no-op until configuration is added. For Wave 4 minimum viable,
    // this method can also be invoked programmatically for testing.
    void scheduledResumePoll() {
        if (!frameworkService) {
            return // not yet initialized
        }
        def serverUUID = frameworkService.serverUUID
        def candidates = findResumeCandidates()
        for (Execution e : candidates) {
            boolean claimed = attemptClaim(e.id, serverUUID)
            if (claimed) {
                try {
                    resumeExecution(e)
                } catch (Throwable t) {
                    log.error("Failed to resume execution ${e.id}: ${t.message}", t)
                    releaseClaim(e.id)
                }
            }
        }
    }

    /**
     * Timeout sweep. Finds executions with
     * {@code wait_timeout_at <= now()} and delivers synthetic timeout
     * payloads. Tick interval from config property
     * {@code rundeck.execution.resume.timeoutSweepIntervalMs} (default
     * 30000ms).
     */
    void scheduledTimeoutSweep() {
        if (!frameworkService) {
            return
        }
        def now = new Date()
        def expired = Execution.withTransaction {
            Execution.findAllByStatusAndResumeReadyAndWaitTimeoutAtLessThanEquals(
                    ExecutionService.EXECUTION_WAITING,
                    false,
                    now
            )
        }
        for (Execution e : expired) {
            log.info("Execution ${e.id} timed out (waitTimeoutAt=${e.waitTimeoutAt}); delivering synthetic timeout payload")
            // Create a minimal synthetic timeout payload as a JSON string.
            // The step plugin's resume invocation will see isTimeout()=true.
            def syntheticPayload = objectMapper.writeValueAsString([
                    type: 'timeout',
                    timeout: true
            ])
            Execution.withNewTransaction {
                Execution exec = Execution.get(e.id)
                if (exec && exec.status == ExecutionService.EXECUTION_WAITING && !exec.resumeReady) {
                    exec.resumeReady = true
                    exec.resumePayload = syntheticPayload
                    exec.save(flush: true)
                }
            }
        }
    }

    /**
     * Called by consumer API controllers to deliver a resume event.
     *
     * @return true if the row was updated; false if the execution is no
     *         longer in 'waiting' state (the caller should return 409).
     */
    boolean markResumeReady(Long execId, ResumePayload payload) {
        String payloadJson = objectMapper.writeValueAsString(payload)
        int updated = 0
        Execution.withNewTransaction {
            updated = Execution.executeUpdate(
                    "update Execution e set e.resumeReady = true, e.resumePayload = :payload " +
                    "where e.id = :id and e.status = :waiting",
                    [id: execId, payload: payloadJson, waiting: ExecutionService.EXECUTION_WAITING]
            )
        }
        return updated > 0
    }

    /**
     * Resume an execution after a successful atomic claim. Rehydrates the
     * execution context from the checkpoint, reopens the log writer in
     * resume mode, spawns a fresh thread, and re-enters the engine via
     * {@code EngineWorkflowExecutor.executeWorkflowResume}.
     *
     * <p>On failure (context rehydration, log open, plugin class not
     * loadable), releases the claim and increments
     * {@code resume_attempt_count}. After max attempts, marks the
     * execution failed.
     *
     * <p>Wave 4 minimum viable: this is a placeholder that logs the
     * resume attempt. The full rehydration + thread-spawn + engine-entry
     * path requires significant wiring (framework service lookup,
     * listener construction, auth context rehydration from frozen
     * user+roles). This placeholder enables the polling worker to be
     * tested end-to-end; the full resume path is layered on iteratively.
     */
    void resumeExecution(Execution execution) {
        log.info("Resuming execution ${execution.id} from checkpoint " +
                 "(suspendedAt step ${execution.checkpointData ? 'present' : 'absent'})")
        // TODO Wave 4 full: rehydrate context, reopen log, spawn thread,
        // enter executeWorkflowResume. For now, log and mark as placeholder.
        // Full implementation requires:
        // 1. Parse checkpoint_data JSON → ExecutionCheckpoint
        // 2. Parse resume_payload JSON → ResumePayload (polymorphic)
        // 3. Parse suspend_metadata JSON → Map
        // 4. Rebuild ExecutionContextImpl from checkpoint + local framework
        //    (mirrors executeAsyncBegin context-build pattern)
        // 5. Open log writer via logFileStorageService.getLogFileWriterForResume
        // 6. Create WorkflowExecutionServiceThread
        // 7. Call EngineWorkflowExecutor.executeWorkflowResume
        // 8. On completion: call saveExecutionState (terminal write)
        // 9. On failure: release claim + increment resume_attempt_count
    }

    /**
     * Operational visibility: return all waiting executions, optionally
     * filtered by project.
     */
    List<Execution> findWaitingExecutions(String project = null) {
        Execution.withTransaction {
            if (project) {
                return Execution.findAllByStatusAndProject(
                        ExecutionService.EXECUTION_WAITING, project)
            }
            return Execution.findAllByStatus(ExecutionService.EXECUTION_WAITING)
        }
    }

    // ---------------------------------------------------------------
    // Internal: claim mechanics mirroring ScheduledExecutionService.
    // claimScheduledJobs pattern.
    // ---------------------------------------------------------------

    private List<Execution> findResumeCandidates() {
        Execution.withTransaction {
            Execution.findAllByStatusAndResumeReadyAndServerNodeUUID(
                    ExecutionService.EXECUTION_WAITING,
                    true,
                    null,  // serverNodeUUID IS NULL
                    [sort: 'waitStartedAt', order: 'asc', max: 10]
            )
        }
    }

    /**
     * Atomic claim: UPDATE ... SET serverNodeUUID=me, status='running',
     * lastResumedAt=now() WHERE id=:id AND status='waiting' AND
     * serverNodeUUID IS NULL AND resumeReady=true.
     *
     * @return true if exactly one row was updated (claim succeeded)
     */
    private boolean attemptClaim(Long execId, String serverUUID) {
        int updated = 0
        Execution.withNewTransaction {
            updated = Execution.executeUpdate(
                    "update Execution e set " +
                    "e.serverNodeUUID = :uuid, " +
                    "e.status = :running, " +
                    "e.lastResumedAt = :now " +
                    "where e.id = :id " +
                    "and e.status = :waiting " +
                    "and e.serverNodeUUID is null " +
                    "and e.resumeReady = true",
                    [uuid: serverUUID, running: ExecutionService.EXECUTION_RUNNING,
                     now: new Date(), id: execId, waiting: ExecutionService.EXECUTION_WAITING]
            )
        }
        if (updated == 1) {
            log.info("Claimed execution ${execId} for resume on node ${serverUUID}")
        }
        return updated == 1
    }

    /**
     * Release claim on failure: clear serverNodeUUID, revert to waiting,
     * increment resume_attempt_count.
     */
    private void releaseClaim(Long execId) {
        def maxAttempts = 3 // TODO: read from config
        Execution.withNewTransaction {
            Execution e = Execution.get(execId)
            if (e) {
                e.resumeAttemptCount = (e.resumeAttemptCount ?: 0) + 1
                if (e.resumeAttemptCount >= maxAttempts) {
                    log.error("Execution ${execId} resume exhausted after ${e.resumeAttemptCount} attempts; marking failed")
                    e.status = ExecutionService.EXECUTION_FAILED
                    e.dateCompleted = new Date()
                } else {
                    log.warn("Execution ${execId} resume attempt ${e.resumeAttemptCount} failed; releasing claim for retry")
                    e.status = ExecutionService.EXECUTION_WAITING
                    e.serverNodeUUID = null
                }
                e.save(flush: true)
            }
        }
    }
}
