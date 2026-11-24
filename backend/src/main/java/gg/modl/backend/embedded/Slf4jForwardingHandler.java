package gg.modl.backend.embedded;

import java.lang.StackWalker.StackFrame;
import java.util.Optional;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;
import org.slf4j.LoggerFactory;

final class Slf4jForwardingHandler extends Handler {
    private static final String UNNAMED_LOGGER = "jul";
    private static final String JUL_PACKAGE = "java.util.logging.";
    private static final StackWalker STACK_WALKER = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);

    private final ClassLoader serverClassLoader;
    private final Logger forwardedLogger;
    private final boolean hostUsesParentHandlers;
    private final SimpleFormatter messageFormatter = new SimpleFormatter();

    Slf4jForwardingHandler(ClassLoader serverClassLoader, Logger forwardedLogger, boolean hostUsesParentHandlers) {
        this.serverClassLoader = serverClassLoader;
        this.forwardedLogger = forwardedLogger;
        this.hostUsesParentHandlers = hostUsesParentHandlers;
    }

    @Override
    public void publish(LogRecord logRecord) {
        if (logRecord == null) {
            return;
        }
        if (loggedByServerCode()) {
            forwardToServerLog(logRecord);
        } else if (hostUsesParentHandlers) {
            passToHostHandlers(logRecord);
        }
    }

    @Override
    public void flush() {
    }

    @Override
    public void close() {
    }

    private boolean loggedByServerCode() {
        return STACK_WALKER.walk(frames -> frames
                .filter(frame -> !isLoggingFrame(frame))
                .findFirst())
            .map(frame -> frame.getDeclaringClass().getClassLoader() == serverClassLoader)
            .orElse(false);
    }

    private boolean isLoggingFrame(StackFrame frame) {
        Class<?> declaringClass = frame.getDeclaringClass();
        return declaringClass == Slf4jForwardingHandler.class || declaringClass.getName().startsWith(JUL_PACKAGE);
    }

    private void passToHostHandlers(LogRecord logRecord) {
        Optional.ofNullable(forwardedLogger.getParent()).ifPresent(parent -> parent.log(logRecord));
    }

    private void forwardToServerLog(LogRecord logRecord) {
        String name = logRecord.getLoggerName() == null ? UNNAMED_LOGGER : logRecord.getLoggerName();
        var logger = LoggerFactory.getLogger(name);
        String message = messageFormatter.formatMessage(logRecord);
        Throwable thrown = logRecord.getThrown();
        int level = logRecord.getLevel().intValue();
        if (level >= Level.SEVERE.intValue()) {
            logger.error(message, thrown);
        } else if (level >= Level.WARNING.intValue()) {
            logger.warn(message, thrown);
        } else if (level >= Level.INFO.intValue()) {
            logger.info(message, thrown);
        } else if (level >= Level.FINE.intValue()) {
            logger.debug(message, thrown);
        } else {
            logger.trace(message, thrown);
        }
    }
}
