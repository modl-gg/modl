package gg.modl.minecraft.core.login;

import gg.modl.minecraft.core.util.PluginLogger;

import java.util.UUID;

public final class InvalidNameNotice {
    private final PluginLogger logger;

    public InvalidNameNotice(PluginLogger logger) {
        this.logger = logger;
    }

    public void refused(UUID uuid) {
        logger.warning("Refused the login of " + uuid + ": its player name does not follow the Minecraft name rule "
                + "(1 to 16 letters, digits, or underscores, or one Bedrock prefix . * + and up to 15 more).");
    }
}
