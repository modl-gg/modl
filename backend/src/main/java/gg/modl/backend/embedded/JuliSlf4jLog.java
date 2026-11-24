package gg.modl.backend.embedded;

import org.apache.juli.logging.Log;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class JuliSlf4jLog implements Log {
    private static final String SERVICE_LOADER_PLACEHOLDER = "org.apache.juli";

    private final Logger logger;

    public JuliSlf4jLog() {
        this(SERVICE_LOADER_PLACEHOLDER);
    }

    public JuliSlf4jLog(String name) {
        this.logger = LoggerFactory.getLogger(name);
    }

    @Override
    public boolean isDebugEnabled() {
        return logger.isDebugEnabled();
    }

    @Override
    public boolean isErrorEnabled() {
        return logger.isErrorEnabled();
    }

    @Override
    public boolean isFatalEnabled() {
        return logger.isErrorEnabled();
    }

    @Override
    public boolean isInfoEnabled() {
        return logger.isInfoEnabled();
    }

    @Override
    public boolean isTraceEnabled() {
        return logger.isTraceEnabled();
    }

    @Override
    public boolean isWarnEnabled() {
        return logger.isWarnEnabled();
    }

    @Override
    public void trace(Object message) {
        logger.trace(String.valueOf(message));
    }

    @Override
    public void trace(Object message, Throwable error) {
        logger.trace(String.valueOf(message), error);
    }

    @Override
    public void debug(Object message) {
        logger.debug(String.valueOf(message));
    }

    @Override
    public void debug(Object message, Throwable error) {
        logger.debug(String.valueOf(message), error);
    }

    @Override
    public void info(Object message) {
        logger.info(String.valueOf(message));
    }

    @Override
    public void info(Object message, Throwable error) {
        logger.info(String.valueOf(message), error);
    }

    @Override
    public void warn(Object message) {
        logger.warn(String.valueOf(message));
    }

    @Override
    public void warn(Object message, Throwable error) {
        logger.warn(String.valueOf(message), error);
    }

    @Override
    public void error(Object message) {
        logger.error(String.valueOf(message));
    }

    @Override
    public void error(Object message, Throwable error) {
        logger.error(String.valueOf(message), error);
    }

    @Override
    public void fatal(Object message) {
        logger.error(String.valueOf(message));
    }

    @Override
    public void fatal(Object message, Throwable error) {
        logger.error(String.valueOf(message), error);
    }
}
