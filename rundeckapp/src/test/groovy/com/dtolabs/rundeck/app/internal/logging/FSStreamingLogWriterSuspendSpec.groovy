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
package com.dtolabs.rundeck.app.internal.logging

import com.dtolabs.rundeck.core.logging.LogUtil
import com.dtolabs.rundeck.core.logging.internal.RundeckLogFormat
import spock.lang.Specification
import spock.lang.TempDir

import java.nio.charset.StandardCharsets
import java.nio.file.Path

/**
 * Wave 3 exit criteria: suspend/resume roundtrip test for
 * {@link FSStreamingLogWriter}. Verifies the file structure is correct
 * across a suspend + resume-mode-reopen + close cycle.
 *
 * <p>Lives in the rundeckapp test tree because FSStreamingLogWriter is
 * a rundeckapp class. Validated by CI (local npm issue prevents running
 * the rundeckapp test suite).
 *
 * <p>See spec §5.2 in docs/specs/workflow-suspend-resume.md.
 */
class FSStreamingLogWriterSuspendSpec extends Specification {

    @TempDir
    Path tempDir

    static final RundeckLogFormat FORMAT = new RundeckLogFormat()

    def "suspend then resume roundtrip produces a valid log file"() {
        given: "a fresh log file and writer"
            File logFile = tempDir.resolve("test-execution.rdlog").toFile()
            def writer1 = new FSStreamingLogWriter(
                    new FileOutputStream(logFile),
                    [:],
                    FORMAT
            )

        when: "write events and suspend (no footer)"
            writer1.openStream()
            writer1.addEvent(LogUtil.logNormal("event-A1"))
            writer1.addEvent(LogUtil.logNormal("event-A2"))
            writer1.suspend()

        then: "file has header + events but no footer"
            def afterSuspend = logFile.getText('UTF-8')
            afterSuspend.startsWith(RundeckLogFormat.FILE_START)
            afterSuspend.contains("event-A1")
            afterSuspend.contains("event-A2")
            !afterSuspend.contains(RundeckLogFormat.FILE_END)

        when: "reopen in resume mode (append), write more events, close normally"
            def writer2 = new FSStreamingLogWriter(
                    new FileOutputStream(logFile, true),
                    [:],
                    FORMAT,
                    true  // resumeMode
            )
            writer2.openStream()
            writer2.addEvent(LogUtil.logNormal("event-B1"))
            writer2.addEvent(LogUtil.logNormal("event-B2"))
            writer2.close()

        then: "final file has exactly one header, all events in order, one footer"
            def finalContent = logFile.getText('UTF-8')
            def lines = logFile.readLines('UTF-8')

            // Exactly one header
            lines.count { it == RundeckLogFormat.FILE_START } == 1

            // Exactly one footer
            lines.count { it == RundeckLogFormat.FILE_END } == 1

            // Events in order
            def posA1 = finalContent.indexOf("event-A1")
            def posA2 = finalContent.indexOf("event-A2")
            def posB1 = finalContent.indexOf("event-B1")
            def posB2 = finalContent.indexOf("event-B2")
            posA1 < posA2
            posA2 < posB1
            posB1 < posB2

            // Header before first event, footer after last event
            finalContent.indexOf(RundeckLogFormat.FILE_START) < posA1
            finalContent.indexOf(RundeckLogFormat.FILE_END) > posB2
    }

    def "suspend is idempotent"() {
        given:
            File logFile = tempDir.resolve("idempotent.rdlog").toFile()
            def writer = new FSStreamingLogWriter(
                    new FileOutputStream(logFile),
                    [:],
                    FORMAT
            )
            writer.openStream()
            writer.addEvent(LogUtil.logNormal("hello"))

        when: "call suspend twice"
            writer.suspend()
            writer.suspend()

        then: "no exception"
            noExceptionThrown()
    }

    def "resume mode does not write a header"() {
        given: "a file pre-populated with a header"
            File logFile = tempDir.resolve("resume-no-header.rdlog").toFile()
            logFile.text = RundeckLogFormat.FILE_START + "\n"

        when:
            def writer = new FSStreamingLogWriter(
                    new FileOutputStream(logFile, true),
                    [:],
                    FORMAT,
                    true
            )
            writer.openStream()
            writer.addEvent(LogUtil.logNormal("resumed-event"))
            writer.close()

        then: "no second header"
            def lines = logFile.readLines('UTF-8')
            lines.count { it == RundeckLogFormat.FILE_START } == 1
            logFile.text.contains("resumed-event")
            logFile.text.contains(RundeckLogFormat.FILE_END)
    }

    def "normal mode writes a header"() {
        given:
            File logFile = tempDir.resolve("normal.rdlog").toFile()

        when:
            def writer = new FSStreamingLogWriter(
                    new FileOutputStream(logFile),
                    [:],
                    FORMAT
            )
            writer.openStream()
            writer.addEvent(LogUtil.logNormal("normal-event"))
            writer.close()

        then:
            def content = logFile.text
            content.startsWith(RundeckLogFormat.FILE_START)
            content.contains("normal-event")
            content.contains(RundeckLogFormat.FILE_END)
    }
}
