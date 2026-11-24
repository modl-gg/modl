package gg.modl.minecraft.core.support;

import gg.modl.minecraft.core.PluginServices;
import gg.modl.minecraft.core.boot.CoreState;
import gg.modl.minecraft.core.cache.Cache;
import gg.modl.minecraft.core.config.ConfigurationReload;
import gg.modl.minecraft.core.connection.PanelUrl;
import gg.modl.minecraft.core.impl.menus.util.ChatInputManager;
import gg.modl.minecraft.core.locale.LocaleManager;
import gg.modl.minecraft.core.staff.action.StaffActionGate;

public final class TestPluginServices {
    private TestPluginServices() {}

    public static PluginServices install(Cache cache, LocaleManager localeManager) {
        return install(cache, localeManager, runningGate(cache, localeManager), null);
    }

    public static PluginServices install(Cache cache, LocaleManager localeManager, StaffActionGate gate,
                                         ChatInputManager chatInput) {
        PluginServices services = new PluginServices(
                cache, localeManager, null, gate, null, chatInput, null, null, PanelUrl.of("https://panel.example.com"),
                null, new ConfigurationReload(localeManager, cache, () -> { }));
        PluginServices.install(services);
        return services;
    }

    public static StaffActionGate runningGate(Cache cache, LocaleManager localeManager) {
        return new StaffActionGate(() -> CoreState.RUNNING, cache, localeManager, null, actor -> false);
    }

    public static PluginServices install(Cache cache) {
        return install(cache, new LocaleManager());
    }
}
