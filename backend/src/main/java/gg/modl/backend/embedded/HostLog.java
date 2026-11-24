package gg.modl.backend.embedded;

import java.util.function.BiConsumer;

final class HostLog {
    private static final String NOTICE = "NOTICE";
    private static final String WARN = "WARN";
    private static final String ERROR = "ERROR";

    private final BiConsumer<String, String> sink;

    HostLog(BiConsumer<String, String> sink) {
        this.sink = sink;
    }

    void notice(String message) {
        sink.accept(NOTICE, message);
    }

    void warn(String message) {
        sink.accept(WARN, message);
    }

    void error(String message) {
        sink.accept(ERROR, message);
    }
}
