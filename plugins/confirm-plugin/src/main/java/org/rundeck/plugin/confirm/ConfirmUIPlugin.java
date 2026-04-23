package org.rundeck.plugin.confirm;

import com.dtolabs.rundeck.core.plugins.Plugin;
import com.dtolabs.rundeck.plugins.descriptions.PluginDescription;
import com.dtolabs.rundeck.plugins.rundeck.UIPlugin;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

@Plugin(name = "confirm-execution-ui", service = "UI")
@PluginDescription(title = "Confirm Execution UI", description = "Renders confirmation panel on execution detail page for confirm steps")
public class ConfirmUIPlugin implements UIPlugin {

    @Override
    public boolean doesApply(String path) {
        return "execution/show".equals(path);
    }

    @Override
    public List<String> resourcesForPath(String path) {
        return Arrays.asList("js/confirm-execution.js", "css/confirm-execution.css");
    }

    @Override
    public List<String> scriptResourcesForPath(String path) {
        return Arrays.asList("js/confirm-execution.js");
    }

    @Override
    public List<String> styleResourcesForPath(String path) {
        return Arrays.asList("css/confirm-execution.css");
    }

    @Override
    public List<String> requires(String path) {
        return Collections.emptyList();
    }
}
