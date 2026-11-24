package gg.modl.minecraft.core.config;

import gg.modl.minecraft.core.cache.Cache;
import gg.modl.minecraft.core.locale.LocaleManager;

import java.util.function.Consumer;

import static gg.modl.minecraft.core.util.Java8Collections.mapOf;

public final class ConfigurationReload {
    private final LocaleManager localeManager;
    private final Cache cache;
    private final Runnable runtimeConfigurationReload;

    public ConfigurationReload(LocaleManager localeManager, Cache cache, Runnable runtimeConfigurationReload) {
        this.localeManager = localeManager;
        this.cache = cache;
        this.runtimeConfigurationReload = runtimeConfigurationReload;
    }

    public void reload(Consumer<String> reply) {
        reply.accept(localeManager.getMessage("general.reloading"));
        try {
            localeManager.reloadLocale();
            cache.clearPunishmentTypes();
            runtimeConfigurationReload.run();
            reply.accept(localeManager.getMessage("general.reload_success"));
        } catch (RuntimeException failure) {
            reply.accept(localeManager.getMessage("general.reload_error",
                    mapOf("error", String.valueOf(failure.getMessage()))));
        }
    }
}
