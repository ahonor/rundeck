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
 * SPI extension on {@link StreamingLogWriter} for writers that can participate
 * in workflow suspension. Adds a single {@link #suspend()} method that flushes,
 * fsyncs to disk, and closes the underlying stream WITHOUT writing a terminal
 * footer.
 *
 * <p>Contract clauses (normative):
 * <ul>
 *   <li>MUST flush buffered in-JVM events before closing.</li>
 *   <li>MUST fsync kernel buffers to disk before returning. A plain
 *       {@code flush()} is insufficient &mdash; crashed processes may lose
 *       kernel-buffered writes that {@code flush()} reported as successful.
 *       On {@code FileOutputStream}-based writers this is
 *       {@code stream.getFD().sync()}.</li>
 *   <li>MUST NOT write a terminal footer (no
 *       {@code formatter.outputFinish()} or equivalent).</li>
 *   <li>After return, the writer instance is closed and must not be used.</li>
 *   <li>Idempotent: a second call to {@code suspend()} on the same instance
 *       is a no-op.</li>
 * </ul>
 *
 * <p>Writers that do NOT implement this interface cause
 * {@link SuspensionPolicy} to reject suspension attempts for workflows
 * configured with those writers. The rejection message names the offending
 * writer class.
 *
 * <p>{@code FSStreamingLogWriter} (the default file-backed log writer in
 * Rundeck OSS) is the reference implementation &mdash; see Wave 3 of
 * {@code docs/cycles/workflow-suspend-resume.md}. Third-party streaming log
 * writer plugins opt in by implementing this sub-interface; until they do,
 * workflows configured with those plugins cannot suspend.
 *
 * <p>See spec section 5.2 in {@code docs/specs/workflow-suspend-resume.md}.
 */
public interface CheckpointableStreamingLogWriter extends StreamingLogWriter {

    /**
     * Flush any buffered events, fsync kernel buffers to disk, and close the
     * underlying stream without writing a terminal footer. The file remains
     * in a state indistinguishable from an in-progress execution; a
     * subsequent {@code openForResume(...)} call on a
     * {@link CheckpointableStreamingLogWriterFactory} may reopen it.
     */
    void suspend();
}
