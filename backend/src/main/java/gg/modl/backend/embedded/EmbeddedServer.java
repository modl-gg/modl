package gg.modl.backend.embedded;

import gg.modl.backend.BackendApplication;
import gg.modl.backend.infrastructure.util.FailureDescriptions;
import gg.modl.backend.update.DeploymentDetector;
import gg.modl.backend.update.install.EmbeddedPanelRestart;
import gg.modl.backend.update.install.EmbeddedUpdateInputs;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Function;
import org.apache.catalina.webresources.TomcatURLStreamHandlerFactory;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

public final class EmbeddedServer implements Function<Map<String, Object>, AutoCloseable> {
    private static final String PROPERTIES_INPUT = "properties";
    private static final String LOG_SINK_INPUT = "logSink";
    private static final String LOG_DIR_INPUT = "logDir";
    private static final String DEFAULT_LOG_DIR = "logs";
    private static final String LOG_FILE_NAME = "server.log";
    private static final String UPDATE_INPUTS_BEAN = "embeddedUpdateInputs";
    private static final String PANEL_RESTART_BEAN = "embeddedPanelRestart";

    @Override
    public AutoCloseable apply(Map<String, Object> input) {
        ClassLoader serverClassLoader = EmbeddedServer.class.getClassLoader();
        HostLog hostLog = new HostLog(logSink(input));
        String logDir = input.get(LOG_DIR_INPUT) instanceof String directory && !directory.isBlank() ? directory : DEFAULT_LOG_DIR;
        String logFile = logDir + "/" + LOG_FILE_NAME;
        return ServerClassLoader.call(serverClassLoader, () -> start(input, hostLog, logDir, logFile, serverClassLoader));
    }

    private static AutoCloseable start(Map<String, Object> input, HostLog hostLog, String logDir, String logFile,
                                       ClassLoader serverClassLoader) {
        TomcatURLStreamHandlerFactory.disable();
        ServerResources serverResources = new ServerResources(serverClassLoader, JulForwarding.install(serverClassLoader));
        boolean started = false;
        try {
            ConfigurableApplicationContext context = application(input, hostLog, logDir, logFile).run();
            started = true;
            return new EmbeddedServerHandle(context, serverClassLoader, serverResources);
        } catch (RuntimeException e) {
            String cause = FailureDescriptions.describe(e);
            hostLog.error("The modl server failed to start: " + cause + " (details in " + logFile + ")");
            throw new IllegalStateException(cause, e);
        } finally {
            if (!started) {
                serverResources.release();
            }
        }
    }

    private static SpringApplication application(Map<String, Object> input, HostLog hostLog, String logDir, String logFile) {
        SpringApplication application = BackendApplication.application(overrides(input, logDir));
        application.setRegisterShutdownHook(false);
        application.setBannerMode(Banner.Mode.OFF);
        application.addListeners(new IsolatedEnvironment(), new EmbeddedLogging(hostLog, logFile));
        EmbeddedUpdateInputs.from(input).ifPresent(updates -> application.addInitializers(context ->
            context.getBeanFactory().registerSingleton(UPDATE_INPUTS_BEAN, updates)));
        EmbeddedPanelRestart.from(input).ifPresent(restart -> application.addInitializers(context ->
            context.getBeanFactory().registerSingleton(PANEL_RESTART_BEAN, restart)));
        return application;
    }

    private static Map<String, Object> overrides(Map<String, Object> input, String logDir) {
        Map<String, Object> overrides = new HashMap<>();
        if (input.get(PROPERTIES_INPUT) instanceof Map<?, ?> properties) {
            properties.forEach((key, value) -> {
                if (key != null && value != null) {
                    overrides.put(key.toString(), value.toString());
                }
            });
        }
        overrides.put("MODL_LOG_DIR", logDir);
        overrides.put(DeploymentDetector.PROPERTY, DeploymentDetector.EMBEDDED);
        overrides.put(IsolatedEnvironment.CONFIG_LOCATION_PROPERTY, IsolatedEnvironment.BUNDLED_CONFIG_LOCATION);
        overrides.put("logging.config", EmbeddedLogging.CONFIGURATION);
        overrides.put("logging.register-shutdown-hook", "false");
        overrides.put("server.shutdown", "immediate");
        overrides.put("spring.main.banner-mode", "off");
        return overrides;
    }

    @SuppressWarnings("unchecked")
    private static BiConsumer<String, String> logSink(Map<String, Object> input) {
        if (input.get(LOG_SINK_INPUT) instanceof BiConsumer<?, ?> sink) {
            return (BiConsumer<String, String>) sink;
        }
        return (level, message) -> {
        };
    }
}
