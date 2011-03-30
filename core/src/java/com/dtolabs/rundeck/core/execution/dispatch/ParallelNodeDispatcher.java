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
* MultiNodeExecutor.java
* 
* User: Greg Schueler <a href="mailto:greg@dtosolutions.com">greg@dtosolutions.com</a>
* Created: 3/21/11 4:03 PM
* 
*/
package com.dtolabs.rundeck.core.execution.dispatch;

import com.dtolabs.rundeck.core.NodesetFailureException;
import com.dtolabs.rundeck.core.cli.CallableWrapperTask;
import com.dtolabs.rundeck.core.cli.NodeDispatchStatusListener;
import com.dtolabs.rundeck.core.common.Framework;
import com.dtolabs.rundeck.core.common.INodeEntry;
import com.dtolabs.rundeck.core.common.NodeFileParserException;
import com.dtolabs.rundeck.core.execution.ExecutionContext;
import com.dtolabs.rundeck.core.execution.ExecutionItem;
import com.dtolabs.rundeck.core.execution.FailedNodesListener;
import com.dtolabs.rundeck.core.execution.StatusResult;
import com.dtolabs.rundeck.core.execution.commands.CommandInterpreter;
import com.dtolabs.rundeck.core.execution.commands.InterpreterResult;
import com.dtolabs.rundeck.core.execution.impl.common.AntSupport;
import com.dtolabs.rundeck.core.tasks.dispatch.NodeExecutionStatusTask;
import com.dtolabs.rundeck.core.utils.NodeSet;
import org.apache.tools.ant.BuildException;
import org.apache.tools.ant.Project;
import org.apache.tools.ant.Task;
import org.apache.tools.ant.taskdefs.Parallel;
import org.apache.tools.ant.taskdefs.Sequential;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * MultiNodeExecutor is ...
 *
 * @author Greg Schueler <a href="mailto:greg@dtosolutions.com">greg@dtosolutions.com</a>
 */
public class ParallelNodeDispatcher implements NodeDispatcher {
    public static final String STATUS_LISTENER_REF_ID = ParallelNodeDispatcher.class.getName() + ":status.listener";
    public static final String NODE_NAME_LOCAL_REF_ID = ParallelNodeDispatcher.class.getName() + ":node.name";
    public static final String NODE_USER_LOCAL_REF_ID = ParallelNodeDispatcher.class.getName() + ":node.user";

    private Framework framework;

    public ParallelNodeDispatcher(Framework framework) {
        this.framework = framework;
    }

    public DispatcherResult dispatch(final ExecutionContext context,
                                     final ExecutionItem item) throws
        DispatcherException {
        return dispatch(context, item, null);
    }

    public DispatcherResult dispatch(final ExecutionContext context,
                                     final Dispatchable item) throws
        DispatcherException {
        return dispatch(context, null, item);
    }

    public DispatcherResult dispatch(final ExecutionContext context,
                                     final ExecutionItem item, final Dispatchable toDispatch) throws
        DispatcherException {
        final NodeSet nodeset = context.getNodeSet();
        Collection<INodeEntry> nodes = null;
        try {
            nodes = framework.filterNodes(nodeset, context.getFrameworkProject());
        } catch (NodeFileParserException e) {
            throw new DispatcherException(e);
        }
        boolean keepgoing = nodeset.isKeepgoing();

        final HashSet<String> nodeNames = new HashSet<String>();
        final NodeDispatchStatusListener listener = new NodeDispatchStatusListener() {
            public void reportSuccess(final String nodename) {
                synchronized (nodeNames) {
                    nodeNames.remove(nodename);
                }
            }
        };
        FailedNodesListener failedListener = context.getExecutionListener().getFailedNodesListener();
        if (null != failedListener) {
            failedListener.matchedNodes(nodeNames);
        }
        Project project = new Project();
//        if (null != context.getExecutionListener().getBuildListener() ) {
//            project.addBuildListener(context.getExecutionListener().getBuildListener());
//        }
        project.addReference(STATUS_LISTENER_REF_ID, listener);
        AntSupport.addAntBuildListener(context.getExecutionListener(), project);

        project.log(
            "preparing for parallel execution...(keepgoing? " + keepgoing + ", threads: " + nodeset.getThreadCount()
            + ")",
            Project.MSG_DEBUG);
        configureNodeContextThreadLocalsForProject(project);
        final Parallel parallelTask = new Parallel();
        parallelTask.setProject(project);
        parallelTask.setThreadCount(nodeset.getThreadCount());
        parallelTask.setFailOnAny(!keepgoing);
        boolean success = false;
        final HashMap<String, StatusResult> resultMap = new HashMap<String, StatusResult>();
        for (final Object node1 : nodes) {
            final INodeEntry node = (INodeEntry) node1;
            final Callable tocall;
            if (null != item) {
                tocall = execItemCallable(context, item, resultMap, node);
            } else {
                tocall = dispatchableCallable(context, toDispatch, resultMap, node);
            }
            nodeNames.add(node.getNodename());
            project.log("Create task for node: " + node.getNodename(), Project.MSG_DEBUG);
            final Task callableWrapperTask = createWrappedCallableTask(project, tocall, node);
            parallelTask.addTask(callableWrapperTask);
        }
        try {
            parallelTask.execute();
            success = true;
        } catch (BuildException e) {
            project.log(e.getMessage(), Project.MSG_ERR);
            if (!keepgoing) {
                throw new DispatcherException(e);
            }
        }
        //evaluate the failed nodes
        if (nodeNames.size() > 0) {
            if (null != failedListener) {
                //tell listener of failed node list
                failedListener.nodesFailed(nodeNames);
            }
            //now fail
            //XXXX: needs to change from exception
            throw new NodesetFailureException(nodeNames);
        } else if (null != failedListener && nodeNames.isEmpty()) {
            failedListener.nodesSucceeded();
        }

        final boolean status = success;

        return new DispatcherResult() {
            public Map<String, StatusResult> getResults() {
                return resultMap;
            }

            public boolean isSuccess() {
                return status;
            }

            @Override
            public String toString() {
                return "Parallel dispatch: (" + isSuccess() + ") " + resultMap;
            }
        };
    }

    private Callable dispatchableCallable(final ExecutionContext context, final Dispatchable toDispatch,
                                          final HashMap<String, StatusResult> resultMap, final INodeEntry node) {
        return new Callable() {
            public Object call() throws Exception {
                final StatusResult dispatch = toDispatch.dispatch(context, node);
                resultMap.put(node.getNodename(), dispatch);
                return dispatch;
            }
        };
    }

    private Callable execItemCallable(final ExecutionContext context, final ExecutionItem item,
                                      final HashMap<String, StatusResult> resultMap, final INodeEntry node) {
        return new Callable() {
            public Object call() throws Exception {
                final InterpreterResult interpreterResult = framework.getExecutionService().interpretCommand(
                    context, item, node);
                resultMap.put(node.getNodename(), interpreterResult);
                return interpreterResult;
            }
        };
    }

    /**
     * Adds InheritableNodeLocal references to the Project for use by the node context tasks
     *
     * @param project the project
     */
    public static void configureNodeContextThreadLocalsForProject(final Project project) {
        final InheritableThreadLocal<String> localNodeName = new InheritableThreadLocal<String>();
        final InheritableThreadLocal<String> localUserName = new InheritableThreadLocal<String>();
        if (null == project.getReference(NODE_NAME_LOCAL_REF_ID)) {
            project.addReference(NODE_NAME_LOCAL_REF_ID, localNodeName);
        }
        if (null == project.getReference(NODE_USER_LOCAL_REF_ID)) {
            project.addReference(NODE_USER_LOCAL_REF_ID, localUserName);
        }
    }

    /**
     * Extract the threadlocal stored as a reference in the project, and return the string value or null.
     *
     * @param nodeNameLocalRefId refid for the thread local variable
     * @param project            Project
     *
     * @return value of the variable, or null if it is not found or the refid doesn't refer to a valid thread local
     */
    public static String getThreadLocalForProject(final String nodeNameLocalRefId, final Project project) {
        final Object o = project.getReference(nodeNameLocalRefId);
        String thrNode = null;
        if (null != o && o instanceof InheritableThreadLocal) {
            InheritableThreadLocal<String> local = (InheritableThreadLocal<String>) o;
            thrNode = local.get();
        }
        return thrNode;
    }

    /**
     * Add tasks to the Sequential to set threadlocal values for the node name and username
     *
     * @param nodeentry node entry
     * @param project   ant Project
     * @param seq       Sequential
     */
    public static void addNodeContextTasks(final INodeEntry nodeentry, final Project project,
                                           final Sequential seq) {
        //set thread local node name
        final Task nodenamelocal = genSetThreadLocalRefValue(NODE_NAME_LOCAL_REF_ID, nodeentry.getNodename());
        nodenamelocal.setProject(project);
        seq.addTask(nodenamelocal);

        if (null != nodeentry.extractUserName()) {
            //set thread local username
            final Task userlocal = genSetThreadLocalRefValue(NODE_USER_LOCAL_REF_ID, nodeentry.extractUserName());
            userlocal.setProject(project);
            seq.addTask(userlocal);
        }

    }

    /**
     * Return a task configured to set the thread local value for a particular refid
     *
     * @param refid the refid
     * @param value the value to set
     *
     * @return the Task
     */
    private static Task genSetThreadLocalRefValue(final String refid, final String value) {
        final setThreadLocalTask task = new setThreadLocalTask();
        task.setRefid(refid);
        task.setValue(value);
        return task;
    }


    /**
     * Task to set a threadlocal value given a refid. The refid should have been set in the project already, and be an
     * InheritableThreadLocal instance.  The value will be set for the threadlocal variable
     */
    public static class setThreadLocalTask extends Task {
        private String value;
        private String refid;

        @Override
        public void execute() throws BuildException {
            final Object o = getProject().getReference(getRefid());
            if (o instanceof InheritableThreadLocal) {
                final InheritableThreadLocal<String> local = (InheritableThreadLocal<String>) o;
                local.set(getValue());
            }
        }

        public String getValue() {
            return value;
        }

        public void setValue(final String value) {
            this.value = value;
        }

        public String getRefid() {
            return refid;
        }

        public void setRefid(final String refid) {
            this.refid = refid;
        }
    }

    /**
     * Return a Sequential Task that sets threadlocal values for Node context information, then executes the Callable
     * via a CallableWrapperTask, then reports node success via NodeExecutionStatusTask.
     *
     * @param project the project
     * @param tocall  the Callable
     * @param node    the target node
     *
     * @return Sequential Task instance
     */
    private Task createWrappedCallableTask(final Project project, final Callable tocall, final INodeEntry node) {
        final Sequential seq = new Sequential();
        seq.setProject(project);
        addNodeContextTasks(node, project, seq);
        final CallableWrapperTask callableWrapperTask = new CallableWrapperTask(tocall);
        callableWrapperTask.setProject(project);
        seq.addTask(callableWrapperTask);
        addNodeContextSuccessReport(node, project, seq);
        return seq;
    }

    /**
     * Add internal success notification to inform parallel node dispatcher that execution was successful on this node.
     *
     * @param nodeentry the node
     * @param project   the project
     * @param seq       the Sequential task
     */
    public static void addNodeContextSuccessReport(final INodeEntry nodeentry, final Project project,
                                                   final Sequential seq) {
        final NodeExecutionStatusTask status = new NodeExecutionStatusTask();
        status.setProject(project);
        status.setNodeName(nodeentry.getNodename());
        status.setRefId(STATUS_LISTENER_REF_ID);
        status.setFailOnError(false);

        seq.addTask(status);
    }
}