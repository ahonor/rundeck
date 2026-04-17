/*
 * Copyright 2016 SimplifyOps, Inc. (http://simplifyops.com)
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

package com.dtolabs.rundeck.app.internal.logging

import com.dtolabs.rundeck.core.execution.workflow.suspend.CheckpointableStreamingLogWriter
import com.dtolabs.rundeck.core.logging.LogEvent
import com.dtolabs.rundeck.core.logging.StreamingLogWriter
import com.dtolabs.rundeck.core.logging.internal.DefaultLogEvent
import com.dtolabs.rundeck.core.logging.internal.OutputLogFormat

/**
 * Logs to a file using the OutputLogFormat
 */
class FSStreamingLogWriter implements StreamingLogWriter, CheckpointableStreamingLogWriter {
    static final String lineSep = "\n"
    private OutputStream output
    private Map<String, String> defaultMeta
    private OutputLogFormat formatter
    private boolean started
    private volatile long bytesWritten
    /**
     * Wave 3 cycle/workflow-suspend-resume: when true, {@link #openStream()}
     * skips writing the {@code ^text/x-rundeck-log-v2.0^} header so that
     * appending to an existing log file does not produce a second header.
     * Used by {@code LogFileStorageService.getLogFileWriterForResume()}.
     */
    private boolean resumeMode

    public long getBytesWritten(){
        return bytesWritten
    }

    /**
     * Create a FSStreamingLogWriter
     * @param output outputstream
     * @param threshold highest log level to emit
     * @param defaultMeta default metadata to add to emitted events, only applies if the metadata is not present
     * @param formatter log format
     */
    public FSStreamingLogWriter(OutputStream output, Map<String, String> defaultMeta,
                                OutputLogFormat formatter) {
        this(output, defaultMeta, formatter, false)
    }

    /**
     * Wave 3 cycle/workflow-suspend-resume: create a writer with an optional
     * resume-mode flag. When {@code resumeMode} is {@code true},
     * {@link #openStream()} does NOT write the format header — the caller
     * has opened the stream in append mode on an existing file that already
     * has a header from its original open. See spec §5.2.
     */
    public FSStreamingLogWriter(OutputStream output, Map<String, String> defaultMeta,
                                OutputLogFormat formatter, boolean resumeMode) {
        this.output = output
        this.defaultMeta = defaultMeta
        this.formatter = formatter
        this.resumeMode = resumeMode
        started = false
        bytesWritten = 0
    }
    private write(String val) {
        def bytes = val.getBytes("UTF-8")
        output.write(bytes)
        bytesWritten += bytes.length
    }
    @Override
    void openStream() throws IOException{
        synchronized (this) {
            if (!started) {
                // Wave 3 cycle/workflow-suspend-resume: in resume mode the
                // file already has a header from its original open; writing
                // a second one would corrupt the format. See spec §5.2.
                if (!resumeMode) {
                    write(formatter.outputBegin())
                    write(lineSep)
                }
                started = true
            }
        }
    }
    private Exception closer;
    @Override
    void addEvent(LogEvent event) {
        synchronized (this) {
            if (null == output) {
                throw new IllegalStateException("output was closed", closer)
            }
            def event1 = formatter.outputEvent(new DefaultLogEvent(event, defaultMeta))
            write(event1)
            write(lineSep)
        }
    }

    void close() {
        synchronized (this) {
            if (null != output) {
                write(formatter.outputFinish())
                write(lineSep)
                output.flush()
                output.close()
                output = null
                //generate stacktrace to record source of close()
                closer = new Exception()
            }
        }
    }

    /**
     * Wave 2 suspend/resume stub: flush and close the underlying stream
     * WITHOUT writing the terminal {@code ^END^} footer. Wave 3 hardens this
     * implementation with an explicit {@code fsync} and adds a resume-mode
     * constructor flag that suppresses {@code outputBegin()} on reopen so
     * the appended content does not produce a second header.
     *
     * <p>See spec §5.2 and cycle manifest Wave 2/3 notes.
     */
    /**
     * Wave 3 cycle/workflow-suspend-resume: flush buffered events, fsync
     * kernel buffers to disk, and close the underlying stream WITHOUT writing
     * the terminal {@code ^END^} footer. The file remains in a state
     * indistinguishable from an in-progress execution; a subsequent
     * {@code openForResume} call can reopen it in append mode.
     *
     * <p>Spec §5.2 contract: MUST fsync before returning. A plain
     * {@code flush()} is insufficient — crashed processes may lose
     * kernel-buffered writes that flush reported as successful.
     */
    @Override
    void suspend() {
        synchronized (this) {
            if (null != output) {
                // Intentionally does NOT call formatter.outputFinish().
                output.flush()
                // fsync: force kernel buffers to disk (spec §5.2 contract).
                if (output instanceof FileOutputStream) {
                    ((FileOutputStream) output).getFD().sync()
                }
                output.close()
                output = null
                closer = new Exception()
            }
        }
    }
}
