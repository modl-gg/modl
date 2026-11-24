package gg.modl.minecraft.velocity;

import gg.modl.minecraft.core.util.PluginLogger;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

@RequiredArgsConstructor
final class VelocityPluginLogger implements PluginLogger {
    private final Logger logger;

    @Override
    public void info(String message) {
        logger.info(message);
    }

    @Override
    public void warning(String message) {
        logger.warn(message);
    }

    @Override
    public void severe(String message) {
        logger.error(message);
    }

    @Override
    public void debug(String message) {
        logger.debug(message);
    }

    @Override
    public void warning(String message, @Nullable Throwable throwable) {
        logger.warn(message, throwable);
    }
}
