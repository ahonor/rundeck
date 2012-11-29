/*
 * Copyright 2012 DTO Labs, Inc. (http://dtolabs.com)
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
* ScriptPluginNodeStepPlugin.java
* 
* User: Greg Schueler <a href="mailto:greg@dtosolutions.com">greg@dtosolutions.com</a>
* Created: 11/16/12 12:37 PM
* 
*/
package com.dtolabs.rundeck.core.execution.workflow.steps.node;

import com.dtolabs.rundeck.core.common.Framework;
import com.dtolabs.rundeck.core.common.INodeEntry;
import com.dtolabs.rundeck.core.plugins.BaseScriptPlugin;
import com.dtolabs.rundeck.core.plugins.PluginException;
import com.dtolabs.rundeck.core.plugins.ScriptPluginProvider;
import com.dtolabs.rundeck.core.plugins.configuration.ConfigurationException;
import com.dtolabs.rundeck.plugins.step.NodeStepPlugin;
import com.dtolabs.rundeck.plugins.step.PluginStepContext;
import com.dtolabs.rundeck.plugins.step.PluginStepItem;

import java.io.IOException;


/**
 * ScriptPluginNodeStepPlugin is a {@link NodeStepPlugin} that uses a {@link ScriptPluginProvider}
 *
 *
 * @author Greg Schueler <a href="mailto:greg@dtosolutions.com">greg@dtosolutions.com</a>
 */
class ScriptPluginNodeStepPlugin extends BaseScriptPlugin implements NodeStepPlugin {

    ScriptPluginNodeStepPlugin(final ScriptPluginProvider provider, final Framework framework) {
        super(provider, framework);
    }

    @Override
    public boolean isAllowCustomProperties() {
        return true;
    }

    static void validateScriptPlugin(final ScriptPluginProvider plugin) throws PluginException {
        try {
            createDescription(plugin, true);
        } catch (ConfigurationException e) {
            throw new PluginException(e);
        }
    }

    @Override
    public boolean executeNodeStep(final PluginStepContext executionContext,
                                   final PluginStepItem item,
                                   final INodeEntry node)
        throws NodeStepException {
        final ScriptPluginProvider plugin = getProvider();
        final String pluginname = plugin.getName();
        executionContext.getLogger().log(3,
                                                    "[" + pluginname + "] step started, config: "
                                                    + item.getStepConfiguration());


        int result = -1;
        try {
            result = runPluginScript(executionContext, item, System.out, System.err, getFramework());
        } catch (IOException e) {
            e.printStackTrace();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        boolean success = result == 0;

        executionContext.getLogger().log(3,
                                         "[" + pluginname + "]: result code: " + result + ", success: "
                                         + success);
        return success;
    }
}
