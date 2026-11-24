package gg.modl.backend.embedded;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.logging.Logger;

final class JulForwarding {
    private static final List<String> PAYLOAD_LOGGERS = List.of(
        "com.google.common",
        "com.google.protobuf"
    );

    private final List<ForwardedLogger> forwarded;

    private JulForwarding(List<ForwardedLogger> forwarded) {
        this.forwarded = forwarded;
    }

    static JulForwarding install(ClassLoader serverClassLoader) {
        return install(serverClassLoader, Logger::getLogger);
    }

    static JulForwarding install(ClassLoader serverClassLoader, Function<String, Logger> loggerLookup) {
        List<ForwardedLogger> forwarded = new ArrayList<>();
        for (String name : PAYLOAD_LOGGERS) {
            Logger logger = loggerLookup.apply(name);
            if (!supportsHandlers(logger)) {
                continue;
            }
            boolean usedParentHandlers = logger.getUseParentHandlers();
            Slf4jForwardingHandler handler = new Slf4jForwardingHandler(serverClassLoader, logger, usedParentHandlers);
            forwarded.add(new ForwardedLogger(logger, usedParentHandlers, handler));
            logger.addHandler(handler);
            logger.setUseParentHandlers(false);
        }
        return new JulForwarding(List.copyOf(forwarded));
    }

    private static boolean supportsHandlers(Logger logger) {
        return logger.getClass() == Logger.class;
    }

    void uninstall() {
        forwarded.forEach(ForwardedLogger::restore);
    }

    private record ForwardedLogger(Logger logger, boolean usedParentHandlers, Slf4jForwardingHandler handler) {
        void restore() {
            logger.removeHandler(handler);
            logger.setUseParentHandlers(usedParentHandlers);
        }
    }
}
