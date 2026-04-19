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

import com.dtolabs.rundeck.core.common.IFramework
import com.dtolabs.rundeck.core.common.NodeSetImpl
import com.dtolabs.rundeck.core.execution.ExecutionContextImpl
import com.dtolabs.rundeck.core.execution.WorkflowExecutionServiceThread
import com.dtolabs.rundeck.core.execution.workflow.EngineWorkflowExecutor
import com.dtolabs.rundeck.core.execution.workflow.WorkflowExecutionListenerImpl
import com.dtolabs.rundeck.core.execution.workflow.NoopWorkflowExecutionListener
import com.dtolabs.rundeck.app.internal.workflow.MultiWorkflowExecutionListener
import com.dtolabs.rundeck.core.logging.LogUtil
import com.dtolabs.rundeck.core.execution.workflow.StepExecutionContext
import com.dtolabs.rundeck.core.execution.workflow.WFSharedContext
import com.dtolabs.rundeck.core.execution.workflow.WorkflowExecutionItem
import com.dtolabs.rundeck.core.execution.workflow.WorkflowExecutionResult
import com.dtolabs.rundeck.core.execution.workflow.suspend.ExecutionCheckpoint
import com.dtolabs.rundeck.core.execution.workflow.suspend.ResumePayload
import com.dtolabs.rundeck.core.dispatcher.ContextView
import com.dtolabs.rundeck.core.logging.LogLevel
import com.dtolabs.rundeck.core.logging.internal.RundeckLogFormat
import com.dtolabs.rundeck.app.internal.logging.FSStreamingLogWriter
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import groovy.util.logging.Slf4j
import org.rundeck.app.authorization.AppAuthContextProcessor
import org.springframework.scheduling.annotation.Scheduled
import rundeck.Execution
import rundeck.ScheduledExecution
import rundeck.services.logging.ExecutionLogWriter

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
    AppAuthContextProcessor rundeckAuthContextProcessor
    def storageService
    def rundeckNodeService
    WorkflowService workflowService

    private final ObjectMapper objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    /**
     * Polling worker. Tick interval from config property
     * {@code rundeck.execution.resume.pollIntervalMs} (default 2000ms).
     *
     * <p>For each eligible row (ordered by wait_started_at ASC for
     * fairness), attempts an atomic claim via UPDATE. On successful claim
     * (1 affected row), calls {@link #resumeExecution(Execution)}. On
     * zero affected rows (race lost), skips.
     */
    @Scheduled(fixedDelayString = '${rundeck.execution.resume.pollIntervalMs:2000}')
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
    void resumeExecution(Execution executionRef) {
        def execId = executionRef.id
        log.info("Resuming execution ${execId} from checkpoint")

        // Wrap the ENTIRE resume path in a single Hibernate session so
        // all lazy proxies (ScheduledExecution, Workflow, plugin configs)
        // resolve consistently. Multiple separate sessions cause
        // LazyInitializationException when objects from one session are
        // accessed in another.
        Execution.withNewSession { session ->

        Execution execution = Execution.get(execId)
        if (!execution) {
            log.error("resumeExecution: execution ${execId} not found")
            releaseClaim(execId)
            return
        }
        // Force Hibernate to re-read the row from the DB to sync the
        // version field after the raw SQL UPDATE in attemptClaim.
        execution.refresh()

        // 1. Parse checkpoint
        ExecutionCheckpoint checkpoint
        try {
            checkpoint = objectMapper.readValue(execution.checkpointData, ExecutionCheckpoint)
        } catch (Exception e) {
            log.error("Failed to parse checkpoint for execution ${execId}: ${e.message}")
            releaseClaim(execId)
            return
        }
        if (checkpoint.version != ExecutionCheckpoint.CURRENT_VERSION) {
            log.error("Unsupported checkpoint version ${checkpoint.version} for execution ${execId}")
            execution.status = ExecutionService.EXECUTION_FAILED
            execution.dateCompleted = new Date()
            execution.save(flush: true)
            return
        }

        // 2. Parse resume payload (polymorphic)
        ResumePayload payload = null
        if (execution.resumePayload) {
            try {
                payload = objectMapper.readValue(execution.resumePayload, ResumePayload)
            } catch (Exception e) {
                log.warn("Failed to parse resume payload for execution ${execId}: ${e.message}; using raw map")
            }
        }

        // 3. Parse suspend metadata
        Map<String, Object> metadata = [:]
        if (execution.suspendMetadata) {
            try {
                metadata = objectMapper.readValue(execution.suspendMetadata, Map)
            } catch (Exception e) {
                log.warn("Failed to parse suspend metadata for execution ${execId}: ${e.message}")
            }
        }

        // 4. Rebuild auth context
        def authContext = rundeckAuthContextProcessor.getAuthContextForUserAndRoles(
                execution.user, execution.userRoles ?: [])

        // 5. Get framework
        IFramework framework = frameworkService.rundeckFramework

        // 6. Reopen log writer in resume mode
        def defaultMeta = [user: execution.user, node: framework.frameworkNodeName]
        def logWriter
        try {
            logWriter = logFileStorageService.getLogFileWriterForResume(execution, defaultMeta)
            logWriter.openStream()
        } catch (Exception e) {
            log.error("Failed to reopen log writer for execution ${execId}: ${e.message}", e)
            releaseClaim(execId)
            return
        }
        def loghandler = new ExecutionLogWriter(logWriter)

        // 7. Build execution context
        def project = execution.project
        // Build the listener chain the same way executeAsyncBegin does:
        // 1. ContextManager tracks current step/node for log event metadata
        // 2. ContextLogWriter stamps log events with step context
        // 3. LoggerWithContext combines them into an ExecutionLogger
        // 4. WorkflowExecutionListenerImpl uses the logger for step lifecycle
        // 5. WorkflowExecutionStateListenerAdapter for persisted step state
        // 6. MultiWorkflowExecutionListener to combine them
        def contextmanager = new com.dtolabs.rundeck.core.execution.workflow.ContextManager()
        // Filter debug/verbose log messages from the execution log, matching
        // the normal execution path's LoglevelThresholdLogWriter behavior.
        def logLevel = com.dtolabs.rundeck.core.logging.LogLevel.looseValueOf(
                execution.loglevel ?: 'INFO', com.dtolabs.rundeck.core.logging.LogLevel.NORMAL)
        def filteredWriter = new rundeck.services.logging.LoglevelThresholdLogWriter(loghandler, logLevel)
        def contextLogWriter = new com.dtolabs.rundeck.core.logging.ContextLogWriter(filteredWriter)
        def resumeLogger = new com.dtolabs.rundeck.core.execution.workflow.LoggerWithContext(
                contextLogWriter, contextmanager)
        def baseListener = new WorkflowExecutionListenerImpl(null, resumeLogger)

        // Create the workflow state listener that persists per-step state
        // to the state file. This is what the UI's execution detail page
        // reads to show step-by-step progress.
        def jobcontext = executionService.exportContextForExecution(
                execution, executionService.grailsLinkGenerator)
        WorkflowExecutionItem item = executionUtilService.createExecutionItemForWorkflow(
                execution.workflowData)
        def execStateListener = workflowService.createWorkflowStateListenerForExecution(
                execution,
                item.workflow,
                framework,
                authContext,
                jobcontext,
                null  // secureOpts not needed for state tracking
        )

        // Combine into a multi-listener. The contextmanager must be in
        // the list so it tracks step begin/end for log context stamping.
        // WorkflowEventLoggerListener is intentionally excluded — its
        // verbose "[workflow] Begin step" messages belong in the output
        // view but not the nodes view.
        def logOutFlusher = new com.dtolabs.rundeck.core.logging.internal.LogFlusher()
        def logErrFlusher = new com.dtolabs.rundeck.core.logging.internal.LogFlusher()
        def executionListener = MultiWorkflowExecutionListener.create(
                baseListener,
                [contextmanager, baseListener, execStateListener, logOutFlusher, logErrFlusher]
        )

        def localNodeName = framework.frameworkNodeName
        def localNode = new com.dtolabs.rundeck.core.common.NodeEntryImpl(localNodeName)
        def nodeSet = new NodeSetImpl()
        nodeSet.putNode(localNode)

        def jobId = execution.scheduledExecution?.extid ?: ""
        def jobName = execution.scheduledExecution?.jobName ?: "adhoc"

        StepExecutionContext executionContext = ExecutionContextImpl.builder()
                .frameworkProject(project)
                .user(execution.user)
                .framework(framework)
                .authContext(authContext)
                .storageTree(storageService.storageTreeWithContext(authContext))
                .nodeService(rundeckNodeService)
                .nodes(nodeSet)
                .nodeSelector(com.dtolabs.rundeck.core.common.SelectorUtils.singleNode(localNodeName))
                .executionListener(executionListener)
                .workflowExecutionListener(executionListener)
                .setContext("job", [
                    successOnEmptyNodeFilter: "false",
                    id: jobId,
                    name: jobName,
                    project: project
                ])
                .resumePayload(payload)
                .suspendMetadata(metadata)
                .stepNumber(1)
                .build()

        // Install thread-bound stdout/stderr streams so command output
        // (e.g., echo "Step 3 done") is captured in the execution log
        // with proper step context, just like the normal execution path.
        executionUtilService.sysThreadBoundOut.installThreadStream(
                loggingService.createLogOutputStream(
                        filteredWriter,
                        com.dtolabs.rundeck.core.logging.LogLevel.NORMAL,
                        contextmanager,
                        logOutFlusher,
                        null
                )
        )
        executionUtilService.sysThreadBoundErr.installThreadStream(
                loggingService.createLogOutputStream(
                        filteredWriter,
                        com.dtolabs.rundeck.core.logging.LogLevel.ERROR,
                        contextmanager,
                        logErrFlusher,
                        null
                )
        )

        // 8. Workflow item already built above for the state listener

        // 9. Execute resume
        // Wave 6 cycle/workflow-suspend-resume: when the job uses the
        // node-first strategy, getExecutorForItem returns a
        // NodeFirstWorkflowExecutor wrapping an EngineWorkflowExecutor. The
        // resume path needs the inner EngineWorkflowExecutor directly because
        // executeWorkflowResume is defined there and operates on the raw
        // commands list. Wrap the workflow in an inner-loop item so the
        // lookup returns the engine executor.
        def resumeItem = com.dtolabs.rundeck.core.execution.workflow
                .NodeFirstWorkflowExecutor.createInnerLoopItem(item.workflow)
        def lookedUp = framework
                .getWorkflowExecutionService()
                .getExecutorForItem(resumeItem)
        if (!(lookedUp instanceof EngineWorkflowExecutor)) {
            throw new IllegalStateException(
                    "Expected EngineWorkflowExecutor for inner-loop resume, got " +
                            lookedUp?.class?.name)
        }
        def engineExecutor = (EngineWorkflowExecutor) lookedUp
        // executeWorkflowResume uses item.getWorkflow().getCommands() which
        // must still point at the real workflow commands. The inner-loop
        // wrapper preserves that.
        item = resumeItem

        log.info("Entering executeWorkflowResume for execution ${execId} " +
                 "at suspended step index ${checkpoint.suspendedStepIndex}, " +
                 "completedStepResults=${checkpoint.completedStepResults?.size() ?: 0}, " +
                 "stateAdapter=${execStateListener?.class?.simpleName}, localNode=${localNodeName}")

        WorkflowExecutionResult result
        try {
            result = engineExecutor.executeWorkflowResume(
                    executionContext, item, checkpoint, payload, metadata,
                    execStateListener, localNodeName)
        } catch (Throwable t) {
            log.error("executeWorkflowResume failed for execution ${execId}: ${t.message}", t)
            loghandler.logError("Resume failed: ${t.message}")
            loghandler.close()
            releaseClaim(execId)
            return
        } finally {
            // Tear down thread-bound stdout/stderr streams
            try {
                executionUtilService.sysThreadBoundOut.removeThreadStream()?.close()
            } catch (Throwable t) {
                log.warn("Could not remove thread-bound stdout on resume: ${t.message}")
            }
            try {
                executionUtilService.sysThreadBoundErr.removeThreadStream()?.close()
            } catch (Throwable t) {
                log.warn("Could not remove thread-bound stderr on resume: ${t.message}")
            }
            // Close the log writer (writes ^END^ footer on normal path)
            if (result != null && !result.isSuspended()) {
                try {
                    loghandler.close()
                } catch (Throwable t) {
                    log.warn("Failed to close log writer for execution ${execId}: ${t.message}")
                }
            } else if (result != null && result.isSuspended()) {
                // Re-suspended: suspend-close (no footer)
                try {
                    executionUtilService.suspendExecution(
                            new ExecutionService.AsyncStarted(
                                    loghandler: loghandler,
                                    execution: execution))
                } catch (Throwable t) {
                    log.warn("Failed to suspend-close log writer: ${t.message}")
                }
            }
        }

        // 10. Handle terminal result
        if (result.isSuspended()) {
            // Re-suspended: persist the new suspension state
            executionService.onWorkflowSuspended(
                    new ExecutionService.AsyncStarted(
                            loghandler: loghandler,
                            execution: execution),
                    result)
            log.info("Execution ${execId} re-suspended at step ${result.suspendRequests?.size() ?: 0}")
        } else {
            // Terminal: write completion state directly in this session
            // (not via saveExecutionState which opens a nested transaction
            // and causes version conflicts with our outer session).
            execution.refresh() // sync version after engine ran
            execution.status = result.isSuccess() ? ExecutionService.EXECUTION_SUCCEEDED : ExecutionService.EXECUTION_FAILED
            execution.dateCompleted = new Date()
            // Clear suspend-related columns per I11
            execution.checkpointData = null
            execution.suspendMetadata = null
            execution.waitStartedAt = null
            execution.waitTimeoutAt = null
            execution.lastResumedAt = null
            execution.resumeReady = false
            execution.resumePayload = null
            execution.resumeAttemptCount = 0
            execution.pauseRequested = false
            execution.save(flush: true)
            log.info("Execution ${execId} resumed and completed with success=${result.isSuccess()}")

            // Write execution report so the activity/history page shows
            // this execution. The normal path does this via
            // saveExecutionState → logExecution; we call it directly.
            executionService.logExecution(
                    null,
                    execution.project,
                    execution.user,
                    result.isSuccess(),
                    execution.status,
                    execId,
                    execution.dateStarted,
                    execution.scheduledExecution?.extid,
                    execution.scheduledExecution?.jobName ?: 'adhoc',
                    execution.scheduledExecution ?
                            (execution.scheduledExecution.groupPath ? execution.scheduledExecution.groupPath + '/' : '') +
                            execution.scheduledExecution.jobName : 'adhoc',
                    false,  // cancelled
                    false,  // timedOut
                    false,  // willRetry
                    null,   // node summary
                    null,   // abortedby
                    execution.succeededNodeList,
                    execution.failedNodeList,
                    execution.filter,
                    execution.uuid,
                    execution.scheduledExecution?.uuid
            )
        }

        } // end withNewSession
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
