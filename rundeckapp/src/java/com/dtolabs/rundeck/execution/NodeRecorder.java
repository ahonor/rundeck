/*
 * Copyright 2010 DTO Labs, Inc. (http://dtolabs.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/*
* NodeRecorder.java
*
* User: Greg Schueler <a href="mailto:greg@dtosolutions.com">greg@dtosolutions.com</a>
* Created: Dec 15, 2010 3:13:18 PM
*
*/
package com.dtolabs.rundeck.execution;

import com.dtolabs.rundeck.core.execution.FailedNodesListener;

import java.util.*;

/**
 * NodeRecorder stores success/failure node list
 *
 * @author Greg Schueler <a href="mailto:greg@dtosolutions.com">greg@dtosolutions.com</a>
 */
public class NodeRecorder implements FailedNodesListener {
    private HashSet<String> matchedNodes;
    private HashSet<String> failedNodes;
    private boolean success=false;

    public NodeRecorder() {
        matchedNodes =new HashSet<String>();
        failedNodes=new HashSet<String>();
        success=false;
    }

    public void nodesFailed(final Collection<String> names) {
        failedNodes.addAll(names);
    }

    public void nodesSucceeded() {
        success = true;
    }

    public void matchedNodes(final Collection<String> names) {
        matchedNodes.addAll(names);
    }

    /**
     * Returns the matched nodes less the failed nodes.
     * @return
     */
    public HashSet<String> getSuccessfulNodes() {
        final HashSet<String> successfulNodes = new HashSet<String>(matchedNodes);
        successfulNodes.removeAll(failedNodes);
        return successfulNodes;
    }

    /**
     * Return the set of failed nodes
     * @return
     */
    public HashSet<String> getFailedNodes() {
        return failedNodes;
    }

    /**
     * Return true if successful
     * @return
     */
    public boolean isSuccess() {
        return success;
    }

    /**
     * Return the set of matched nodes
     * @return
     */
    public HashSet<String> getMatchedNodes() {
        return matchedNodes;
    }
}
