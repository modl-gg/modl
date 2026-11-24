package gg.modl.backend.support;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import gg.modl.backend.infrastructure.logging.OperatorNotice;
import java.time.Duration;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.LoggerFactory;

public final class CapturedOperatorNotices implements AutoCloseable {
    private static final Duration WAIT = Duration.ofSeconds(10);
    private static final Duration POLL = Duration.ofMillis(50);

    private final Logger logger = (Logger) LoggerFactory.getLogger(OperatorNotice.LOGGER_NAME);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    public CapturedOperatorNotices() {
        appender.start();
        logger.addAppender(appender);
    }

    public Matcher awaitMatch(Pattern pattern) throws InterruptedException {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (System.nanoTime() < deadline) {
            for (ILoggingEvent event : List.copyOf(appender.list)) {
                Matcher matcher = pattern.matcher(event.getFormattedMessage());
                if (matcher.find()) {
                    return matcher;
                }
            }
            Thread.sleep(POLL);
        }
        throw new AssertionError("No operator notice matched " + pattern);
    }

    @Override
    public void close() {
        logger.detachAppender(appender);
    }
}
