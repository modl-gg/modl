package gg.modl.minecraft.features;

import gg.modl.minecraft.api.http.ModlHttpClient;
import gg.modl.minecraft.features.config.ServerFeaturesConfig;
import gg.modl.minecraft.features.config.ServerFeaturesManagedConfigs;
import gg.modl.minecraft.features.config.StaffModeConfig;
import gg.modl.minecraft.features.locale.FeaturesLocaleManager;
import gg.modl.minecraft.features.link.CoreLink;
import gg.modl.minecraft.features.moderation.LocalModerationOps;
import gg.modl.minecraft.features.moderation.ModerationReconciler;
import gg.modl.minecraft.features.replay.MemberReplayCapture;
import gg.modl.minecraft.features.reporter.AutoReporter;
import gg.modl.minecraft.features.reporter.TicketCreator;
import gg.modl.minecraft.features.reporter.detection.ViolationTracker;
import gg.modl.minecraft.features.reporter.hook.AntiCheatHook;
import gg.modl.minecraft.features.resource.FeaturesYamlResource;
import gg.modl.minecraft.features.statwipe.MemberStatWipeHandler;
import gg.modl.minecraft.features.statwipe.StatWipeHandler;
import gg.modl.minecraft.core.locale.LocaleManager;
import gg.modl.minecraft.core.network.MemberActionSender;
import gg.modl.minecraft.core.network.MemberStatWipes;
import gg.modl.minecraft.core.network.ReplayCaptureHandler;
import gg.modl.minecraft.core.service.ReplayService;
import gg.modl.minecraft.core.util.PluginLogger;
import lombok.Getter;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

public abstract class AbstractServerFeatures {
    private final List<FeaturesReloadStep> reloadSteps = new CopyOnWriteArrayList<>();
    private final OnlinePlayers onlinePlayers = new OnlinePlayers();
    private static final String EMPTY_SERVER_NAME_WARNING = "[server-features] server-name is empty in "
            + "server-features.yml; set it to this server's name in the proxy config so staff hotbar actions work.";

    protected final FeaturesPluginContext context;
    protected final PluginLogger pluginLogger;
    protected final List<AntiCheatHook> hooks = new ArrayList<>();
    protected final ViolationTracker violationTracker = new ViolationTracker();

    @Getter protected final ServerFeaturesConfig featuresConfig;
    @Getter private final StatWipeHandler statWipeHandler;
    private volatile @Nullable StartedFeatures started;

    protected AbstractServerFeatures(FeaturesPluginContext context, ServerFeaturesConfig featuresConfig,
                                      PluginLogger pluginLogger) {
        this.context = context;
        this.featuresConfig = featuresConfig;
        this.pluginLogger = pluginLogger;
        this.statWipeHandler = new StatWipeHandler(pluginLogger, featuresConfig, context.getPlayerProvider());
    }

    public static ServerFeaturesConfig loadConfig(FeaturesResources resources, PluginLogger logger) {
        FeaturesYamlResource.ensureDefaultFile(resources, ServerFeaturesManagedConfigs.SERVER_FEATURES, logger);
        try {
            return ServerFeaturesConfig.load(resources.getDataFolder());
        } catch (IOException e) {
            logger.severe("[server-features] Failed to load server-features.yml: " + e.getMessage());
            return new ServerFeaturesConfig();
        }
    }

    public void enableStandalone(TicketCreator ticketCreator, MemberActionSender localActions,
                                 Supplier<String> serverName, LocaleManager coreLocale) {
        startFeatures(ticketCreator, localActions, null, serverName, new FeaturesLocaleManager(coreLocale));
    }

    public void enableMember(TicketCreator ticketCreator, CoreLink coreLink, Supplier<String> serverName) {
        warnIfServerNameMissing();
        startFeatures(ticketCreator, coreLink, coreLink, serverName,
                FeaturesLocaleManager.forDataFolder(context.getDataFolder(), pluginLogger));
    }

    private void warnIfServerNameMissing() {
        if (!featuresConfig.hasServerName()) pluginLogger.warning(EMPTY_SERVER_NAME_WARNING);
    }

    private void startFeatures(TicketCreator ticketCreator, MemberActionSender actions, @Nullable CoreLink coreLink,
                               Supplier<String> serverName, FeaturesLocaleManager localeManager) {
        if (started != null) throw new IllegalStateException("the server features are already enabled");
        FeaturesYamlResource.ensureDefaultFile(context, ServerFeaturesManagedConfigs.STAFF_MODE, pluginLogger);
        StaffModeConfig staffModeConfig = StaffModeConfig.load(context.getDataFolder(), pluginLogger);
        violationTracker.startCleanupTask(context.getScheduler());

        LocalModerationOps moderationOps = startModerationHandlers(localeManager, staffModeConfig, actions,
                serverName, coreLink != null);
        ReplayService replayService = initReplayRecording(featuresConfig);
        AutoReporter autoReporter = new AutoReporter(pluginLogger, featuresConfig, ticketCreator, violationTracker,
                serverName);
        if (replayService != null) autoReporter.setReplayService(replayService);
        ModerationReconciler moderationReconciler = ModerationReconciler.start(moderationOps, pluginLogger);

        started = new StartedFeatures(localeManager, staffModeConfig, autoReporter, moderationReconciler,
                replayService);
        registerRuntimeHooks(localeManager, coreLink);
    }

    public void disable() {
        StartedFeatures running = started;
        started = null;
        if (running != null) running.moderationReconciler.stop();
        onDisable();
        violationTracker.stopCleanupTask();
        unregisterAntiCheatHooks();
    }

    public FeatureScheduler getScheduler() {
        return context.getScheduler();
    }

    public ModerationReconciler getModerationReconciler() {
        return started().moderationReconciler;
    }

    public @Nullable ReplayService getReplayService() {
        StartedFeatures running = started;
        return running == null ? null : running.replayService;
    }

    public ReplayCaptureHandler memberReplayCaptures() {
        return new MemberReplayCapture(context.getScheduler(), context.getPlayerProvider(), this::getReplayService, pluginLogger);
    }

    public MemberStatWipes memberStatWipes(Supplier<ModlHttpClient> httpClient, Supplier<String> serverName) {
        return new MemberStatWipeHandler(statWipeHandler, context.getScheduler(), httpClient, serverName, pluginLogger);
    }

    public int onlinePlayerCount() {
        return onlinePlayers.count();
    }

    protected void playerJoined(UUID uuid) {
        onlinePlayers.joined(uuid);
        StartedFeatures running = started;
        if (running != null) running.moderationReconciler.onJoin(uuid);
    }

    protected void playerLeft(UUID uuid) {
        onlinePlayers.left(uuid);
        violationTracker.resetPlayer(uuid);
        StartedFeatures running = started;
        if (running == null) return;
        running.autoReporter.clearCooldown(uuid);
        running.moderationReconciler.onQuit(uuid);
    }

    protected final @Nullable AutoReporter startedAutoReporter() {
        StartedFeatures running = started;
        return running == null ? null : running.autoReporter;
    }

    private void registerRuntimeHooks(FeaturesLocaleManager localeManager, @Nullable CoreLink coreLink) {
        applyAntiCheatHookSetting();
        registerPlatformEvents();
        registerServerCommand(localeManager);

        if (coreLink != null) {
            registerProxyCommand(coreLink, localeManager);
        }
    }

    public final FeaturesReloadResult reload() {
        StartedFeatures running = started();
        Path dataFolder = context.getDataFolder();
        List<String> failures = new ArrayList<>();
        List<String> restartRequired = new ArrayList<>();

        FeaturesYamlResource.ensureDefaultFile(context, ServerFeaturesManagedConfigs.SERVER_FEATURES, pluginLogger);
        FeaturesYamlResource.ensureDefaultFile(context, ServerFeaturesManagedConfigs.STAFF_MODE, pluginLogger);

        try {
            ServerFeaturesConfig reloaded = ServerFeaturesConfig.load(dataFolder);
            restartRequired.addAll(featuresConfig.settingsNeedingRestart(reloaded));
            featuresConfig.applyReloadableSettings(reloaded);
        } catch (IOException | RuntimeException e) {
            pluginLogger.warning("[server-features] Failed to reload server-features.yml", e);
            failures.add("server-features.yml");
        }

        try {
            running.staffModeConfig.reload(dataFolder);
        } catch (RuntimeException e) {
            pluginLogger.warning("[server-features] Failed to reload staff_mode.yml", e);
            failures.add("staff_mode.yml");
        }

        try {
            running.localeManager.reload();
        } catch (RuntimeException e) {
            pluginLogger.warning("[server-features] Failed to reload the locale file", e);
            failures.add("locale");
        }

        try {
            applyAntiCheatHookSetting();
        } catch (RuntimeException e) {
            pluginLogger.warning("[anticheat] Failed to reapply the anticheat hooks", e);
            failures.add("anticheat-hooks");
        }

        for (FeaturesReloadStep step : reloadSteps) {
            try {
                step.reload();
            } catch (RuntimeException e) {
                pluginLogger.warning("[server-features] Failed to reload " + step.name() + ": " + e.getMessage());
                failures.add(step.name());
            }
        }

        return new FeaturesReloadResult(failures, restartRequired);
    }

    public final void addReloadStep(FeaturesReloadStep step) {
        reloadSteps.add(step);
    }

    protected final void applyAntiCheatHookSetting() {
        unregisterAntiCheatHooks();
        if (!featuresConfig.isAnticheatHookEnabled()) {
            pluginLogger.info("[anticheat] Anticheat auto-reporting is disabled by anticheat-hook-enabled");
            return;
        }
        registerAntiCheatHooks(hooks, started().autoReporter);
    }

    public final boolean isAnticheatHookEnabled() {
        return featuresConfig.isAnticheatHookEnabled();
    }

    private void unregisterAntiCheatHooks() {
        hooks.forEach(AntiCheatHook::unregister);
        hooks.clear();
    }

    private StartedFeatures started() {
        StartedFeatures running = started;
        if (running == null) throw new IllegalStateException("the server features have not been enabled");
        return running;
    }

    protected abstract void registerServerCommand(FeaturesLocaleManager localeManager);

    protected abstract LocalModerationOps startModerationHandlers(FeaturesLocaleManager localeManager,
                                                                  StaffModeConfig staffModeConfig,
                                                                  MemberActionSender actions,
                                                                  Supplier<String> serverName,
                                                                  boolean coreOnAnotherServer);

    protected abstract void registerAntiCheatHooks(List<AntiCheatHook> hooks, AutoReporter autoReporter);

    protected abstract @Nullable ReplayService initReplayRecording(ServerFeaturesConfig config);

    protected abstract void registerPlatformEvents();

    protected abstract void registerProxyCommand(CoreLink coreLink, FeaturesLocaleManager localeManager);

    protected abstract void onDisable();

    private static final class StartedFeatures {
        private final FeaturesLocaleManager localeManager;
        private final StaffModeConfig staffModeConfig;
        private final AutoReporter autoReporter;
        private final ModerationReconciler moderationReconciler;
        private final @Nullable ReplayService replayService;

        private StartedFeatures(FeaturesLocaleManager localeManager, StaffModeConfig staffModeConfig,
                                AutoReporter autoReporter, ModerationReconciler moderationReconciler,
                                @Nullable ReplayService replayService) {
            this.localeManager = localeManager;
            this.staffModeConfig = staffModeConfig;
            this.autoReporter = autoReporter;
            this.moderationReconciler = moderationReconciler;
            this.replayService = replayService;
        }
    }
}
