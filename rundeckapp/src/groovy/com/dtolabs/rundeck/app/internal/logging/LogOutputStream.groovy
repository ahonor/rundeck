package com.dtolabs.rundeck.app.internal.logging

import com.dtolabs.rundeck.core.logging.LogLevel
import com.dtolabs.rundeck.core.logging.StreamingLogWriter

/*
 * Copyright 2013 DTO Labs, Inc. (http://dtolabs.com)
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
 *
 */

/*
 * LogOutputStream.java
 * 
 * User: Greg Schueler <a href="mailto:greg@dtosolutions.com">greg@dtosolutions.com</a>
 * Created: 1/22/13 10:10 PM
 * 
 */

class LogOutputStream extends OutputStream {
    StreamingLogWriter logger;
    LogLevel level;
    StringBuilder sb;

    def LogOutputStream(StreamingLogWriter logger, LogLevel level) {
        this.logger = logger;
        this.level = level;
        sb = new StringBuilder();
    }

    def boolean crchar = false;

    public void write(final int b) {
        if (b == '\n') {
            logger.addEvent(level,[:], sb.toString() );
            sb = new StringBuilder()
            crchar = false;
        } else if (b == '\r') {
            crchar = true;
        } else {
            if (crchar) {
                logger.addEvent(level,[:], sb.toString() );
                sb = new StringBuilder()
                crchar = false;
            }
            sb.append((char) b)
        }

    }

    public void flush() {
        if (sb.size() > 0) {
            logger.addEvent(level,[:], sb.toString() );
        }
    }
}
