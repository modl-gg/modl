package gg.modl.minecraft.core.embedded;

import com.alessiodp.libby.logging.LogLevel;
import com.alessiodp.libby.logging.adapters.LogAdapter;
import gg.modl.minecraft.core.util.PluginLogger;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;

@RequiredArgsConstructor
final class DebugLogAdapter implements LogAdapter {
    private final PluginLogger logger;

    @Override
    public void log(LogLevel level, @Nullable String message) {
        if (message != null) logger.debug(message);
    }

    @Override
    public void log(LogLevel level, @Nullable String message, @Nullable Throwable throwable) {
        if (throwable == null) {
            log(level, message);
            return;
        }
        logger.debug((message == null ? "" : message + " ") + "(" + throwable + ")");
    }
}
