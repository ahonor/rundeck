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
* NodeSetImpl.java
* 
* User: Greg Schueler <a href="mailto:greg@dtosolutions.com">greg@dtosolutions.com</a>
* Created: 7/19/11 10:46 AM
* 
*/
package com.dtolabs.rundeck.core.common;

import java.util.Collection;
import java.util.HashMap;
import java.util.TreeMap;

/**
 * NodeSetImpl is ...
 *
 * @author Greg Schueler <a href="mailto:greg@dtosolutions.com">greg@dtosolutions.com</a>
 */
public class NodeSetImpl implements INodeSet, NodeReceiver{
    TreeMap<String,INodeEntry> nodes;

    public NodeSetImpl() {
        this(new HashMap<String, INodeEntry>());
    }
    public NodeSetImpl(final HashMap<String, INodeEntry> nodes) {
        this.nodes = new TreeMap<String, INodeEntry>(nodes);
    }

    public void putNode(INodeEntry node) {
        nodes.put(node.getNodename(), node);
    }

    public Collection<INodeEntry> getNodes() {
        return nodes.values();
    }

    public INodeEntry getNode(final String name) {
        return nodes.get(name);
    }

    public Collection<String> getNodeNames() {
        return nodes.keySet();
    }

}
