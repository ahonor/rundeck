/*
 * Copyright 2011 DTO Solutions, Inc. (http://dtosolutions.com)
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

/*
* ExecCommandInterpreter.java
* 
* User: Greg Schueler <a href="mailto:greg@dtosolutions.com">greg@dtosolutions.com</a>
* Created: 3/21/11 4:10 PM
* 
*/
package com.dtolabs.rundeck.core.execution.commands;

import com.dtolabs.rundeck.core.cli.ExecTool;
import com.dtolabs.rundeck.core.common.Framework;
import com.dtolabs.rundeck.core.common.INodeEntry;
import com.dtolabs.rundeck.core.execution.*;
import com.dtolabs.rundeck.core.execution.ExecutionContext;
import com.dtolabs.rundeck.core.execution.service.ExecutionServiceException;
import com.dtolabs.rundeck.core.execution.service.NodeExecutor;
import com.dtolabs.rundeck.core.execution.service.NodeExecutorResult;
import com.dtolabs.rundeck.core.utils.FormattedOutputStream;
import com.dtolabs.rundeck.core.utils.LogReformatter;
import com.dtolabs.rundeck.core.utils.ThreadBoundOutputStream;

import java.io.OutputStream;
import java.util.HashMap;

/**
 * ExecCommandInterpreter is ...
 *
 * @author Greg Schueler <a href="mailto:greg@dtosolutions.com">greg@dtosolutions.com</a>
 */
public class ExecCommandInterpreter implements CommandInterpreter {

    public static final String SERVICE_IMPLEMENTATION_NAME = "exec";

    public ExecCommandInterpreter(Framework framework) {
        this.framework = framework;
    }

    private Framework framework;

    public InterpreterResult interpretCommand(ExecutionContext context, ExecutionItem item, INodeEntry node) throws
        InterpreterException {
        final ExecCommand cmd = (ExecCommand) item;
        NodeExecutorResult result;
        final ExecutionListener listener = context.getExecutionListener();
        final LogReformatter gen;
        gen = createLogReformatter(node, listener);
        //bind System printstreams to the thread
        final ThreadBoundOutputStream threadBoundSysOut = ThreadBoundOutputStream.bindSystemOut();
        final ThreadBoundOutputStream threadBoundSysErr = ThreadBoundOutputStream.bindSystemErr();

        //get outputstream for reformatting destination
        final OutputStream origout = threadBoundSysOut.getThreadStream();
        final OutputStream origerr = threadBoundSysErr.getThreadStream();

        //replace any existing logreformatter
        final FormattedOutputStream outformat;
        if (origout instanceof FormattedOutputStream) {
            final OutputStream origsink = ((FormattedOutputStream) origout).getOriginalSink();
            outformat = new FormattedOutputStream(gen, origsink);
        } else {
            outformat = new FormattedOutputStream(gen, origout);
        }
        outformat.setContext("level", "INFO");

        final FormattedOutputStream errformat;
        if (origerr instanceof FormattedOutputStream) {
            final OutputStream origsink = ((FormattedOutputStream) origerr).getOriginalSink();
            errformat = new FormattedOutputStream(gen, origsink);
        } else {
            errformat = new FormattedOutputStream(gen, origerr);
        }
        errformat.setContext("level", "ERROR");

        //install the OutputStreams for the thread
        threadBoundSysOut.installThreadStream(outformat);
        threadBoundSysErr.installThreadStream(errformat);
        try {
            result = framework.getExecutionService().executeCommand(context, cmd.getCommand(), node);
        } catch (ExecutionException e) {
            throw new InterpreterException(e);
        } finally {
            threadBoundSysOut.removeThreadStream();
            threadBoundSysErr.removeThreadStream();
        }
        return result;
    }

    public static  LogReformatter createLogReformatter(INodeEntry node, ExecutionListener listener) {
        LogReformatter gen;
        if (null != listener && listener.isTerse()) {
            gen = null;
        } else {
            String logformat = ExecTool.DEFAULT_LOG_FORMAT;
            if (null != listener && null != listener.getLogFormat()) {
                logformat = listener.getLogFormat();
            }
            final HashMap<String, String> contextData = new HashMap<String, String>();
            //discover node name and username
            contextData.put("node", node.getNodename());
            contextData.put("user", node.extractUserName());
            contextData.put("command", "test");
            gen = new LogReformatter(logformat, contextData);
        }
        return gen;
    }
}