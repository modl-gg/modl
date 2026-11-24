package gg.modl.minecraft.core.service;

import gg.modl.minecraft.api.http.response.StartupResponse;
import gg.modl.minecraft.core.plugin.PluginInfo;
import gg.modl.minecraft.core.util.PluginLogger;
import gg.modl.update.ReleaseVersion;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

public final class UpdateNotice {
    private static final Pattern RELEASE_PAGE = Pattern.compile("https?://[\\x21-\\x7e]{1,500}");

    private final PluginLogger logger;
    private final AtomicBoolean announced = new AtomicBoolean();

    public UpdateNotice(PluginLogger logger) {
        this.logger = logger;
    }

    public void announceOnce(StartupResponse startup, boolean enabled) {
        if (!enabled || startup == null) return;
        ReleaseVersion latest = ReleaseVersion.tryParse(startup.getLatestVersion());
        if (latest == null || !announced.compareAndSet(false, true)) return;
        logger.warning("modl " + latest + " is available (this plugin is " + PluginInfo.VERSION + ").");
        String page = startup.getLatestReleaseUrl();
        if (page != null && RELEASE_PAGE.matcher(page).matches()) logger.warning("Release notes: " + page);
    }
}
