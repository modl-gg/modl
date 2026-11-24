package gg.modl.backend.embedded;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class JulForwardingTest {
    private static final String PAYLOAD_LOGGER = "com.google.common";

    @Test
    void payloadLibrariesLogToTheServerLogInsteadOfTheHostConsole() {
        Logger julLogger = Logger.getLogger(PAYLOAD_LOGGER);
        var serverLogger = ((LoggerContext) LoggerFactory.getILoggerFactory()).getLogger(PAYLOAD_LOGGER);
        ListAppender<ILoggingEvent> captured = new ListAppender<>();
        captured.start();
        serverLogger.addAppender(captured);
        boolean parentHandlersBefore = julLogger.getUseParentHandlers();
        JulForwarding forwarding = JulForwarding.install(JulForwardingTest.class.getClassLoader());
        try {
            julLogger.log(Level.WARNING, "cache {0} evicted", "entries");

            assertThat(julLogger.getUseParentHandlers()).isFalse();
            assertThat(captured.list).anySatisfy(event -> assertThat(event.getFormattedMessage()).isEqualTo("cache entries evicted"));
        } finally {
            forwarding.uninstall();
            serverLogger.detachAppender(captured);
        }

        assertThat(julLogger.getUseParentHandlers()).isEqualTo(parentHandlersBefore);
        assertThat(Arrays.stream(julLogger.getHandlers())).noneMatch(Slf4jForwardingHandler.class::isInstance);
    }

    @Test
    void recordsFromHostCodeStillReachTheHostHandlers() throws Exception {
        Logger julLogger = Logger.getLogger(PAYLOAD_LOGGER);
        List<LogRecord> hostRecords = new ArrayList<>();
        Handler hostHandler = new Handler() {
            @Override
            public void publish(LogRecord logRecord) {
                hostRecords.add(logRecord);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        Logger parent = julLogger.getParent();
        parent.addHandler(hostHandler);
        try (URLClassLoader foreignServerLoader = new URLClassLoader(new URL[0], null)) {
            JulForwarding forwarding = JulForwarding.install(foreignServerLoader);
            try {
                julLogger.log(Level.WARNING, "host plugin warning");
            } finally {
                forwarding.uninstall();
            }
        } finally {
            parent.removeHandler(hostHandler);
        }

        assertThat(hostRecords).anySatisfy(logRecord -> assertThat(logRecord.getMessage()).isEqualTo("host plugin warning"));
    }

    @Test
    void loggersOwnedByAHostLoggingBridgeAreLeftUntouched() {
        BridgedLogger bridged = new BridgedLogger("okhttp3");
        Logger plain = Logger.getAnonymousLogger();
        boolean bridgedParentHandlersBefore = bridged.getUseParentHandlers();
        JulForwarding forwarding = JulForwarding.install(
            JulForwardingTest.class.getClassLoader(),
            name -> name.equals("okhttp3") ? bridged : plain);
        try {
            assertThat(bridged.handlersAdded).isZero();
            assertThat(bridged.getUseParentHandlers()).isEqualTo(bridgedParentHandlersBefore);
            assertThat(Arrays.stream(plain.getHandlers())).anyMatch(Slf4jForwardingHandler.class::isInstance);
        } finally {
            forwarding.uninstall();
        }
        assertThat(Arrays.stream(plain.getHandlers())).noneMatch(Slf4jForwardingHandler.class::isInstance);
    }

    private static final class BridgedLogger extends Logger {
        private int handlersAdded;

        private BridgedLogger(String name) {
            super(name, null);
        }

        @Override
        public void addHandler(Handler handler) {
            handlersAdded++;
            super.addHandler(handler);
        }
    }
}
