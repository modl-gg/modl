package gg.modl.backend.embedded;

import ch.qos.logback.classic.LoggerContext;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.boot.context.logging.LoggingApplicationListener;
import org.springframework.context.ApplicationListener;
import org.springframework.core.Ordered;

final class EmbeddedLogging implements ApplicationListener<ApplicationEnvironmentPreparedEvent>, Ordered {
    static final String CONFIGURATION = "classpath:logback-embedded.xml";

    private static final String HOST_APPENDER = "HOST";
    private static final Duration SUMMARY_INTERVAL = Duration.ofMinutes(5);
    private static final int AFTER_LOGGING_SYSTEM = LoggingApplicationListener.DEFAULT_ORDER + 1;

    private final HostLog hostLog;
    private final String logFile;

    EmbeddedLogging(HostLog hostLog, String logFile) {
        this.hostLog = hostLog;
        this.logFile = logFile;
    }

    @Override
    public void onApplicationEvent(ApplicationEnvironmentPreparedEvent event) {
        if (!(LoggerFactory.getILoggerFactory() instanceof LoggerContext context)) {
            return;
        }
        HostLogAppender appender = new HostLogAppender(hostLog, logFile, SUMMARY_INTERVAL);
        appender.setName(HOST_APPENDER);
        appender.setContext(context);
        appender.start();
        context.getLogger(Logger.ROOT_LOGGER_NAME).addAppender(appender);
    }

    @Override
    public int getOrder() {
        return AFTER_LOGGING_SYSTEM;
    }

    static void shutdown() {
        if (LoggerFactory.getILoggerFactory() instanceof LoggerContext context) {
            context.stop();
        }
    }
}
