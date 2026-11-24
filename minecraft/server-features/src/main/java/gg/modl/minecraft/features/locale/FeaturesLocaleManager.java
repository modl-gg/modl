package gg.modl.minecraft.features.locale;

import gg.modl.minecraft.core.PluginConfiguration;
import gg.modl.minecraft.core.locale.LegacyTextRenderer;
import gg.modl.minecraft.core.locale.LocaleManager;
import gg.modl.minecraft.core.locale.MessageRenderer;
import gg.modl.minecraft.core.util.PluginLogger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;
import java.util.function.UnaryOperator;

public class FeaturesLocaleManager {
    private static final String SECTION = "server_features.";
    private static final String LOCALE_DIRECTORY = "locale";
    private static final String LOCALE_SUFFIX = ".yml";

    private final UnaryOperator<LocaleManager> reloader;
    private volatile LocaleManager messages;

    public FeaturesLocaleManager(LocaleManager shared) {
        this(shared, current -> {
            current.reloadLocale();
            return current;
        });
    }

    private FeaturesLocaleManager(LocaleManager messages, UnaryOperator<LocaleManager> reloader) {
        this.messages = messages;
        this.reloader = reloader;
    }

    public static FeaturesLocaleManager forDataFolder(Path dataFolder, PluginLogger logger) {
        return new FeaturesLocaleManager(load(dataFolder, logger), previous -> load(dataFolder, logger));
    }

    public void reload() {
        messages = reloader.apply(messages);
    }

    public String getMessage(String key, Map<String, String> placeholders) {
        return messages.getMessage(SECTION + key, placeholders);
    }

    public String getMessage(String key) {
        return getMessage(key, Collections.<String, String>emptyMap());
    }

    public String colorize(String text) {
        return LegacyTextRenderer.colorize(text);
    }

    private static LocaleManager load(Path dataFolder, PluginLogger logger) {
        String locale = PluginConfiguration.configuredLocale(dataFolder, logger);
        LocaleManager loaded;
        try {
            loaded = new LocaleManager(locale);
        } catch (RuntimeException unknownLocale) {
            logger.warning("[server-features] The locale '" + locale + "' is not bundled; using en_US");
            loaded = new LocaleManager();
        }
        loaded.setRenderer(new MessageRenderer());
        loaded.setMissingKeyReporter(logger::warning);
        Path override = dataFolder.resolve(LOCALE_DIRECTORY).resolve(locale + LOCALE_SUFFIX);
        if (Files.exists(override)) loaded.loadFromFile(override);
        return loaded;
    }
}
