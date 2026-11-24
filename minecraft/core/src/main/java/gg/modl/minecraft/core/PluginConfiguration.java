package gg.modl.minecraft.core;

import gg.modl.minecraft.core.config.PackagedConfig;
import gg.modl.minecraft.core.config.RuntimeConfigSource;
import gg.modl.minecraft.core.util.PluginLogger;
import gg.modl.minecraft.core.util.YamlValues;
import org.jspecify.annotations.Nullable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class PluginConfiguration {
    public static final String DEFAULT_SERVER_NAME = "Server 1";
    private static final String COMMANDS = "commands";
    private static final String MUTED_COMMANDS = "muted_commands";
    private static final String REDACTED_COMMANDS = "redacted_commands";
    private static final String LOGGING = "logging";
    private static final String LOG_CHAT = "chat";
    private static final String LOG_COMMANDS = "commands";
    private static final String NEVER_LOGGED_COMMANDS = "never_logged_commands";

    private PluginConfiguration() {
    }

    public static String configuredLocale(Path dataDirectory, PluginLogger logger) {
        return readLocaleFromConfig(RuntimeConfigSource.load(dataDirectory, logger).root());
    }

    public static boolean updateChecksEnabled(Path dataDirectory, PluginLogger logger) {
        return loadUpdateCheckerConfig(RuntimeConfigSource.load(dataDirectory, logger).root(), logger).enabled;
    }

    static String readLocaleFromConfig(Map<String, Object> config) {
        if (config.containsKey("locale")) {
            String locale = (String) config.get("locale");
            if (locale != null && !locale.isEmpty()) {
                return locale;
            }
        }
        return "en_US";
    }

    static List<String> loadRedactedCommands(Map<String, Object> config, PluginLogger logger) {
        return commandList(config, REDACTED_COMMANDS, logger);
    }

    static List<String> loadMutedCommands(Map<String, Object> config, PluginLogger logger) {
        return commandList(config, MUTED_COMMANDS, logger);
    }

    static LoggingConfig loadLoggingConfig(Map<String, Object> config, PluginLogger logger) {
        Map<String, Object> defaults = PackagedConfig.section(LOGGING);
        Object configured = config.get(LOGGING);
        Map<?, ?> logging = configured instanceof Map ? (Map<?, ?>) configured : Collections.emptyMap();
        if (configured != null && !(configured instanceof Map)) {
            logger.warning("logging in config.yml must be a section; chat and command logging keep their defaults.");
        }
        return new LoggingConfig(
                YamlValues.toBoolean(logging.get(LOG_CHAT), YamlValues.toBoolean(defaults.get(LOG_CHAT), true)),
                YamlValues.toBoolean(logging.get(LOG_COMMANDS), YamlValues.toBoolean(defaults.get(LOG_COMMANDS), true)),
                commandList(logging.containsKey(NEVER_LOGGED_COMMANDS) ? logging.get(NEVER_LOGGED_COMMANDS)
                        : defaults.get(NEVER_LOGGED_COMMANDS), LOGGING + "." + NEVER_LOGGED_COMMANDS, logger));
    }

    private static List<String> commandList(Map<String, Object> config, String key, PluginLogger logger) {
        Object configured = config.containsKey(key) ? config.get(key) : PackagedConfig.root().get(key);
        return commandList(configured, key, logger);
    }

    private static List<String> commandList(@Nullable Object configured, String key, PluginLogger logger) {
        if (configured == null) return Collections.emptyList();
        if (configured instanceof String) return Collections.singletonList((String) configured);
        if (!(configured instanceof List)) {
            logger.warning(key + " in config.yml must be a list of command names; it is treated as empty.");
            return Collections.emptyList();
        }
        List<String> commands = new ArrayList<>();
        for (Object command : (List<?>) configured) {
            if (command != null) commands.add(String.valueOf(command));
        }
        return Collections.unmodifiableList(commands);
    }

    @SuppressWarnings("unchecked")
    static Map<String, String> loadCommandAliases(Map<String, Object> config, PluginLogger logger) {
        Map<String, String> aliases = packagedCommandAliases();
        try {
            if (config.containsKey(COMMANDS)) {
                Map<String, Object> commands = (Map<String, Object>) config.get(COMMANDS);
                if (commands != null) {
                    for (Map.Entry<String, Object> entry : commands.entrySet()) {
                        Object rawValue = entry.getValue();
                        if (rawValue == null || String.valueOf(rawValue).trim().isEmpty()) {
                            logger.warning("Command '" + entry.getKey() + "' has a blank alias in config and will be disabled.");
                            aliases.put(entry.getKey(), "");
                        } else {
                            aliases.put(entry.getKey(), String.valueOf(rawValue));
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.warning("Failed to load command aliases from config: " + e.getMessage());
        }
        return aliases;
    }

    private static Map<String, String> packagedCommandAliases() {
        Map<String, String> aliases = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : PackagedConfig.section(COMMANDS).entrySet()) {
            aliases.put(entry.getKey(), entry.getValue() == null ? "" : String.valueOf(entry.getValue()));
        }
        return aliases;
    }

    @SuppressWarnings("unchecked")
    static UpdateCheckerConfig loadUpdateCheckerConfig(Map<String, Object> config, PluginLogger logger) {
        boolean enabled = true;

        try {
            if (config.containsKey("update_checker")) {
                Object updateCheckerNode = config.get("update_checker");
                if (updateCheckerNode instanceof Map) {
                    Map<String, Object> updateChecker = (Map<String, Object>) updateCheckerNode;

                    enabled = YamlValues.toBoolean(updateChecker.get("enabled"), enabled);
                }
            }
        } catch (Exception e) {
            logger.warning("Failed to load update checker config: " + e.getMessage());
        }

        return new UpdateCheckerConfig(enabled);
    }

    static final class LoggingConfig {
        final boolean chat;
        final boolean commands;
        final List<String> neverLoggedCommands;

        LoggingConfig(boolean chat, boolean commands, List<String> neverLoggedCommands) {
            this.chat = chat;
            this.commands = commands;
            this.neverLoggedCommands = neverLoggedCommands;
        }
    }

    static final class UpdateCheckerConfig {
        final boolean enabled;

        private UpdateCheckerConfig(boolean enabled) {
            this.enabled = enabled;
        }
    }
}
