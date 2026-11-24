package gg.modl.minecraft.features.statwipe;

import gg.modl.minecraft.api.PlayerNames;
import gg.modl.minecraft.features.FeaturePlayerProvider;
import gg.modl.minecraft.features.config.ServerFeaturesConfig;
import gg.modl.minecraft.core.util.PluginLogger;
import lombok.RequiredArgsConstructor;

import java.util.List;

@RequiredArgsConstructor
public class StatWipeHandler {
    private static final String PLAYER_PLACEHOLDER = "{player}";
    private static final String UUID_PLACEHOLDER = "{uuid}";

    private final PluginLogger logger;
    private final ServerFeaturesConfig config;
    private final FeaturePlayerProvider playerProvider;

    public boolean execute(String username, String uuid, String punishmentId) {
        if (!PlayerNames.isValid(username)) {
            logger.warning("Refused a stat wipe: the player name is not safe to put in a console command");
            return false;
        }
        List<String> commands = config.getStatWipeCommands();

        if (commands.isEmpty()) {
            logger.warning("No stat-wipe-commands configured");
            return false;
        }

        boolean allSuccess = true;
        for (String template : commands) {
            String command = template
                    .replace(PLAYER_PLACEHOLDER, username)
                    .replace(UUID_PLACEHOLDER, uuid);
            try {
                if (config.isDebug()) {
                    logger.info("Dispatching command: " + command);
                }
                playerProvider.dispatchConsoleCommand(command);
            } catch (Exception e) {
                logger.severe("Failed to execute command '" + command + "': " + e.getMessage());
                allSuccess = false;
            }
        }

        if (allSuccess) {
            logger.info("Executed " + commands.size() + " stat-wipe command(s) for " + username
                    + " (punishment: " + punishmentId + ")");
        }
        return allSuccess;
    }
}
