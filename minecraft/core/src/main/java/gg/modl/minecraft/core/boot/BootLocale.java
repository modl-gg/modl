package gg.modl.minecraft.core.boot;

import gg.modl.minecraft.core.PluginConfiguration;
import gg.modl.minecraft.core.locale.LocaleManager;
import gg.modl.minecraft.core.util.PluginLogger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

final class BootLocale {
    private static final String FALLBACK_LOCALE = "en_US";

    private final LocaleManager manager;

    private BootLocale(LocaleManager manager) {
        this.manager = manager;
    }

    static BootLocale load(Path dataFolder, PluginLogger logger) {
        String locale = configuredLocale(dataFolder, logger);
        LocaleManager manager;
        try {
            manager = new LocaleManager(locale);
        } catch (RuntimeException unknownLocale) {
            logger.warning("Unknown locale " + locale + ", using " + FALLBACK_LOCALE);
            locale = FALLBACK_LOCALE;
            manager = new LocaleManager(locale);
        }
        Path override = dataFolder.resolve("locale").resolve(locale + ".yml");
        if (!Files.exists(override)) return new BootLocale(manager);
        try {
            manager.loadFromFile(override);
            return new BootLocale(manager);
        } catch (RuntimeException | LinkageError unreadable) {
            logger.warning("Ignoring " + override + " for the login message because it could not be read: " + unreadable.getMessage());
            return new BootLocale(new LocaleManager(locale));
        }
    }

    String message(String key, Map<String, String> placeholders) {
        return manager.getMessage(key, placeholders);
    }

    private static String configuredLocale(Path dataFolder, PluginLogger logger) {
        try {
            return PluginConfiguration.configuredLocale(dataFolder, logger);
        } catch (RuntimeException | LinkageError unreadable) {
            logger.warning("Could not read the locale from config.yml, using " + FALLBACK_LOCALE + ": " + unreadable.getMessage());
            return FALLBACK_LOCALE;
        }
    }
}
