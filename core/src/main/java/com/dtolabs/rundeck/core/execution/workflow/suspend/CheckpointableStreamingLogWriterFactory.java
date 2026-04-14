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

import com.dtolabs.rundeck.core.logging.StreamingLogWriter;

/**
 * Factory for opening a log writer in resume mode: append to the existing
 * file at the execution's log path, skip writing the initial header.
 *
 * <p>Used by the resume worker after a successful atomic claim (invariant I6
 * step 5). The returned writer behaves identically to a normal writer for the
 * remainder of the execution: its {@code close()} writes the terminal
 * {@code ^END^} footer. This is the ONLY place the footer is written, and
 * only at true execution completion (invariant I4).
 *
 * <p>Preconditions for {@code openForResume}:
 * <ul>
 *   <li>Any prior writer on the log file has been
 *       {@link CheckpointableStreamingLogWriter#suspend()}ed.</li>
 *   <li>DB state reflects {@code serverNodeUUID} transfer to the caller (the
 *       atomic claim succeeded).</li>
 * </ul>
 *
 * <p>Postconditions:
 * <ul>
 *   <li>Returned writer is open in append mode.</li>
 *   <li>No additional header is written; the file retains its original
 *       {@code ^text/x-rundeck-log-v2.0^} header from the original open.</li>
 *   <li>Calling {@code close()} on the returned writer writes the terminal
 *       footer.</li>
 * </ul>
 *
 * <p>Wave 0 declares the contract; the concrete implementation on
 * {@code LogFileStorageService} lands in Wave 3. See
 * {@code docs/cycles/workflow-suspend-resume.md}.
 *
 * @param <E> execution reference type &mdash; parameterized so the core
 *            interface does not depend on the Grails {@code Execution}
 *            domain class. Implementations in {@code rundeckapp} bind
 *            {@code E = Execution}.
 */
public interface CheckpointableStreamingLogWriterFactory<E> {

    /**
     * Open a streaming log writer in resume mode for the given execution.
     *
     * @param execution the execution whose log file should be reopened in
     *                  append mode
     * @return a writer ready to receive events for the remainder of the
     *         execution
     */
    StreamingLogWriter openForResume(E execution);
}
