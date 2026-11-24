package gg.modl.minecraft.core.embedded;

import gg.modl.minecraft.core.util.PluginLogger;
import lombok.RequiredArgsConstructor;

import java.util.function.BiConsumer;

@RequiredArgsConstructor
final class EmbeddedLogSink implements BiConsumer<String, String> {
    private static final String WARN = "WARN";
    private static final String ERROR = "ERROR";

    private final PluginLogger logger;

    @Override
    public void accept(String level, String message) {
        if (message == null) return;
        if (ERROR.equals(level)) logger.severe(message);
        else if (WARN.equals(level)) logger.warning(message);
        else logger.info(message);
    }
}
