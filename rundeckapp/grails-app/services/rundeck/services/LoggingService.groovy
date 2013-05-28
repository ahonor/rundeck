package rundeck.services

import com.dtolabs.rundeck.app.internal.logging.LogOutputStream
import com.dtolabs.rundeck.core.logging.LogLevel
import com.dtolabs.rundeck.core.logging.StreamingLogWriter
import com.dtolabs.rundeck.core.plugins.configuration.Description
import com.dtolabs.rundeck.plugins.logging.StreamingLogReaderPlugin
import com.dtolabs.rundeck.plugins.logging.StreamingLogWriterPlugin
import com.dtolabs.rundeck.server.plugins.RundeckPluginRegistry
import com.dtolabs.rundeck.server.plugins.services.StreamingLogReaderPluginProviderService
import com.dtolabs.rundeck.server.plugins.services.StreamingLogWriterPluginProviderService
import rundeck.Execution
import rundeck.services.logging.ExecutionLogReader
import rundeck.services.logging.ExecutionLogWriter
import rundeck.services.logging.LogState
import rundeck.services.logging.LoglevelThresholdLogWriter
import rundeck.services.logging.MultiLogWriter

class LoggingService {

    FrameworkService frameworkService
    LogFileStorageService logFileStorageService
    def RundeckPluginRegistry rundeckPluginRegistry
    def StreamingLogWriterPluginProviderService streamingLogWriterPluginProviderService
    def StreamingLogReaderPluginProviderService streamingLogReaderPluginProviderService
    def grailsApplication

    def configure() {
    }

    def StreamingLogReaderPlugin getLogReaderPlugin(String name) {
        def bean = rundeckPluginRegistry?.loadPluginByName(name, streamingLogReaderPluginProviderService)
        if (bean!=null) {
            return (StreamingLogReaderPlugin) bean
        }
        log.error("StreamingLogReaderPlugin not found: ${name}")
        return bean
    }

    def Map validateReaderPluginConfig(String name, Map config) {
        def Map pluginDesc = getReaderPluginDescriptor(name)
        if (pluginDesc && pluginDesc.description instanceof Description) {
            return frameworkService.validateDescription(pluginDesc.description, '', config)
        } else {
            return null
        }
    }
    /**
     *
     * @param name
     * @return map containing [instance:(plugin instance), description: (map or Description), ]
     */
    def Map getReaderPluginDescriptor(String name) {
        def bean = rundeckPluginRegistry?.loadPluginDescriptorByName(name, streamingLogReaderPluginProviderService)
        if (bean) {
            return (Map) bean
        }
        log.error("StreamingLogReaderPlugin not found: ${name}")
        return null
    }

    private StreamingLogReaderPlugin configureStreamingReaderPlugin(String name, Map configuration) {
        def bean = rundeckPluginRegistry?.configurePluginByName(name, streamingLogReaderPluginProviderService, configuration)
        if (bean) {
            return (StreamingLogReaderPlugin) bean
        }
        log.error("StreamingLogReaderPlugin not found: ${name}")
        return null
    }

    def Map listLogReaderPlugins() {
        def plugins = [:]
        plugins = rundeckPluginRegistry?.listPluginDescriptors(StreamingLogReaderPlugin, streamingLogReaderPluginProviderService)
        //clean up name of any Groovy plugin without annotations that ends with "NotificationPlugin"
        plugins.each { key, Map plugin ->
            def desc = plugin.description
            if (desc && desc instanceof Map) {
                if (desc.name.endsWith("StreamingLogReaderPlugin")) {
                    desc.name = desc.name.replaceAll(/StreamingLogReaderPlugin$/, '')
                }
            }
        }
//        System.err.println("listed plugins: ${plugins}")

        plugins
    }

    def StreamingLogWriterPlugin getLogWriterPlugin(String name) {
        def bean = rundeckPluginRegistry?.loadPluginByName(name, streamingLogWriterPluginProviderService)
        if (bean) {
            return (StreamingLogWriterPlugin) bean
        }
        log.error("StreamingLogReaderPlugin not found: ${name}")
        return null
    }

    def Map validateWriterPluginConfig(String name, Map config) {
        def Map pluginDesc = getReaderPluginDescriptor(name)
        if (pluginDesc && pluginDesc.description instanceof Description) {
            return frameworkService.validateDescription(pluginDesc.description, '', config)
        } else {
            return null
        }
    }
    /**
     *
     * @param name
     * @return map containing [instance:(plugin instance), description: (map or Description), ]
     */
    def Map getWriterPluginDescriptor(String name) {
        def bean = rundeckPluginRegistry?.loadPluginDescriptorByName(name, streamingLogWriterPluginProviderService)
        if (bean) {
            return (Map) bean
        }
        log.error("StreamingLogWriterPlugin not found: ${name}")
        return null
    }

    private StreamingLogWriterPlugin configureStreamingWriterPlugin(String name, Map configuration) {
        def bean = rundeckPluginRegistry?.configurePluginByName(name, streamingLogWriterPluginProviderService, configuration)
        if (bean) {
            return (StreamingLogWriterPlugin) bean
        }
        log.error("StreamingLogWriterPlugin not found: ${name}")
        return null
    }

    def Map listLogWriterPlugins() {
        def plugins = [:]
        plugins = rundeckPluginRegistry?.listPluginDescriptors(StreamingLogWriterPlugin, streamingLogWriterPluginProviderService)
        //clean up name of any Groovy plugin without annotations that ends with "NotificationPlugin"
        plugins.each { key, Map plugin ->
            def desc = plugin.description
            if (desc && desc instanceof Map) {
                if (desc.name.endsWith("StreamingLogWriterPlugin")) {
                    desc.name = desc.name.replaceAll(/StreamingLogWriterPlugin$/, '')
                }
            }
        }
//        System.err.println("listed plugins: ${plugins}")

        plugins
    }

    public ExecutionLogWriter openLogWriter(Execution execution, LogLevel level, Map<String, String> defaultMeta) {
        def plugins=[]
        if (grailsApplication.config.rundeck?.execution?.logs?.streamingWriterPlugins) {
            def names = grailsApplication.config.rundeck?.execution?.logs?.streamingWriterPlugins.toString().split(/,\s*/) as List
            def found=[:]
            log.debug("Using log writer plugins ${names}")
            names.each {name->
                found[name]= getLogWriterPlugin(name)
                if(null==found[name]){
                    log.error("Unable to load StreamingLogWriter plugin named ${name}")
                }
                //TODO: configure each plugin from properties
            }
            plugins = found.values() as List
        }
        //TODO: configuration to disable file storage
        plugins << logFileStorageService.getLogFileWriterForExecution(execution, defaultMeta)

        def multiWriter = new MultiLogWriter(plugins)
        def thresholdWriter = new LoglevelThresholdLogWriter(multiWriter, level)
        def writer = new ExecutionLogWriter(thresholdWriter)
        //file path support
        writer.filepath = logFileStorageService.generateFilepathForExecution(execution)
        return writer
    }

    public ExecutionLogReader getLogReader(Execution execution) {
        ExecutionLogReader reader
        if(grailsApplication.config.rundeck?.execution?.logs?.streamingReaderPlugin){
            def pluginName = grailsApplication.config.rundeck.execution.logs.streamingReaderPlugin
            def plugin =  getLogReaderPlugin(pluginName)
            if(plugin==null){
                throw new RuntimeException("Unable to load reader plugin: ${pluginName}")
            }
            //TODO: configure plugin from properties
            HashMap<String, String> jobcontext = contextForExecution(execution)
            log.debug("Using log reader plugin ${pluginName}")
            plugin.initialize(jobcontext)
            return new ExecutionLogReader(state: LogState.FOUND_LOCAL, reader: plugin)
        }else{
            reader = logFileStorageService.requestLogFileReader(execution)
        }

        return reader
    }

    private HashMap<String, String> contextForExecution(Execution execution) {
        def jobcontext = new HashMap<String, String>()
        if (execution.scheduledExecution) {
            jobcontext.name = execution.scheduledExecution.jobName
            jobcontext.group = execution.scheduledExecution.groupPath
            jobcontext.id = execution.scheduledExecution.extid
        }
        jobcontext.execid = execution.id.toString()
        jobcontext.username = execution.user
        jobcontext['user.name'] = execution.user
        jobcontext.project = execution.project
        jobcontext.loglevel = ExecutionService.textLogLevels[execution.loglevel] ?: execution.loglevel
        jobcontext
    }

    public OutputStream createLogOutputStream(StreamingLogWriter logWriter, LogLevel level) {
        return new LogOutputStream(logWriter, level)
    }
}
