package gg.modl.minecraft.spigot.features;

import gg.modl.minecraft.core.connection.BackendConnections;
import gg.modl.minecraft.core.network.MemberActionSender;
import gg.modl.minecraft.core.util.PluginLogger;
import gg.modl.minecraft.features.AbstractServerFeatures;
import gg.modl.minecraft.features.FeatureTask;
import gg.modl.minecraft.features.FeaturesReloadPresenter;
import gg.modl.minecraft.core.feature.FeatureSettings;
import gg.modl.minecraft.features.config.ServerFeaturesConfig;
import gg.modl.minecraft.features.config.StaffModeConfig;
import gg.modl.minecraft.features.link.CoreLink;
import gg.modl.minecraft.features.locale.FeaturesLocaleManager;
import gg.modl.minecraft.features.moderation.FeatureModerationOps;
import gg.modl.minecraft.features.moderation.LocalModerationOps;
import gg.modl.minecraft.features.reporter.AutoReporter;
import gg.modl.minecraft.features.reporter.ModlBackendReplayUploader;
import gg.modl.minecraft.features.reporter.hook.AntiCheatHook;
import gg.modl.minecraft.spigot.features.command.ModlServerCommand;
import gg.modl.minecraft.spigot.features.command.ProxyCmdCommand;
import gg.modl.minecraft.spigot.features.handler.FreezeHandler;
import gg.modl.minecraft.spigot.features.handler.MemberFreezeChatGuard;
import gg.modl.minecraft.spigot.features.handler.StaffModeHandler;
import gg.modl.minecraft.spigot.features.reporter.hook.GrimHook;
import gg.modl.minecraft.spigot.features.reporter.hook.PolarHook;
import gg.modl.minecraft.spigot.features.reporter.hook.VulcanHook;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

public class ServerFeaturesComponent extends AbstractServerFeatures implements Listener {
    private final JavaPlugin plugin;
    private final boolean polarLoaderAvailable;
    private @Nullable StaffModeHandler staffModeHandler;

    private static final String GRIM_PLUGIN = "GrimAC";
    private static final String VULCAN_PLUGIN = "Vulcan";
    private static final List<String> ANTICHEAT_PLUGINS = Collections.unmodifiableList(Arrays.asList(GRIM_PLUGIN, VULCAN_PLUGIN));

    private static final String PACKET_EVENTS_MISSING =
            "[replay] PacketEvents is not installed or not initialized, so this server records no replays";
    private static final long UPLOAD_SHUTDOWN_GRACE_SECONDS = 5L;

    private final LocalReplayFiles replayFiles;
    private volatile @Nullable SpigotReplays recorder;
    private volatile @Nullable FeatureTask cleanupTask;
    private final Set<SpigotReplays> stoppingRecorders = ConcurrentHashMap.newKeySet();
    private boolean packetEventsMissingNoticed;
    private volatile @Nullable ModlBackendReplayUploader replayUploader;

    public ServerFeaturesComponent(JavaPlugin plugin, ServerFeaturesConfig featuresConfig, PluginLogger logger,
                                   boolean polarLoaderAvailable) {
        super(new SpigotFeaturesContext(plugin), featuresConfig, logger);
        this.plugin = plugin;
        this.polarLoaderAvailable = polarLoaderAvailable;
        this.replayFiles = LocalReplayFiles.of(plugin);
    }

    public void uploadReplaysTo(BackendConnections connections) {
        replaceReplayUploader(ModlBackendReplayUploader.direct(connections));
    }

    public void stopReplayUploads() {
        replaceReplayUploader(null);
    }

    @Override
    protected LocalModerationOps startModerationHandlers(FeaturesLocaleManager localeManager,
                                                         StaffModeConfig staffModeConfig, MemberActionSender actions,
                                                         Supplier<String> serverName, boolean coreOnAnotherServer) {
        FreezeHandler freezeHandler = new FreezeHandler(plugin, localeManager, pluginLogger, context.getScheduler());
        StaffModeHandler staffMode = new StaffModeHandler(plugin, freezeHandler, localeManager, pluginLogger,
                staffModeConfig, context.getScheduler(), actions, serverName);
        freezeHandler.register();
        if (coreOnAnotherServer) {
            new MemberFreezeChatGuard(plugin, localeManager, context.getScheduler(), freezeHandler.getFreezeCore(),
                    staffMode).register();
        }
        staffModeHandler = staffMode;
        staffMode.register();
        return new FeatureModerationOps(freezeHandler.getFreezeCore(), staffMode.getCore());
    }

    @Override
    protected void registerAntiCheatHooks(List<AntiCheatHook> hooks, AutoReporter autoReporter) {
        if (Bukkit.getPluginManager().getPlugin(GRIM_PLUGIN) != null) {
            GrimHook grimHook = new GrimHook(plugin, featuresConfig, violationTracker, autoReporter);
            grimHook.register();
            hooks.add(grimHook);
        }

        if (Bukkit.getPluginManager().getPlugin(VULCAN_PLUGIN) != null) {
            VulcanHook vulcanHook = new VulcanHook(plugin, featuresConfig, violationTracker, autoReporter);
            vulcanHook.register();
            hooks.add(vulcanHook);
        }

        attachPolarIfLoaded(hooks, autoReporter);

        if (hooks.isEmpty() && !polarLoaderAvailable) {
            pluginLogger.debug("[anticheat] No anticheat plugins detected. Install GrimAC, Vulcan, or Polar for anticheat reporting.");
        }
    }

    public void hookPolar() {
        context.getScheduler().runOnMainThread(this::hookPolarOnMainThread);
    }

    private void hookPolarOnMainThread() {
        if (!isAnticheatHookEnabled()) return;
        AutoReporter autoReporter = startedAutoReporter();
        if (autoReporter == null) {
            pluginLogger.warning("Polar enable callback fired but the server features are not fully initialized");
            return;
        }
        attachPolarIfLoaded(hooks, autoReporter);
    }

    private void attachPolarIfLoaded(List<AntiCheatHook> hooks, AutoReporter autoReporter) {
        for (AntiCheatHook hook : hooks) {
            if (hook instanceof PolarHook) return;
        }
        PolarHook polarHook = new PolarHook(plugin, featuresConfig, violationTracker, autoReporter);
        if (!polarHook.isAvailable()) return;
        polarHook.register();
        if (polarHook.isRegistered()) {
            hooks.add(polarHook);
            featureStatusChanged();
        }
    }

    @Override
    protected void applyReplaySettings(FeatureSettings previous, FeatureSettings next) {
        SpigotReplays running = recorder;
        if (!next.isReplaysEnabled()) {
            stopRecorder();
            return;
        }
        if (running != null) {
            running.retune(next);
            return;
        }
        if (!supportsReplays()) {
            if (!packetEventsMissingNoticed) pluginLogger.warning(PACKET_EVENTS_MISSING);
            packetEventsMissingNoticed = true;
            return;
        }
        SpigotReplays started = SpigotReplays.start(plugin, next, context.getScheduler(), pluginLogger, () -> replayUploader,
                context.getMinecraftVersion(), replayFiles);
        recorder = started;
        replays.use(started);
    }

    @Override
    protected boolean supportsReplays() {
        return SpigotReplays.packetEventsReady();
    }

    @Override
    protected List<String> detectedAnticheats() {
        List<String> detected = new ArrayList<>();
        for (String name : ANTICHEAT_PLUGINS) {
            if (Bukkit.getPluginManager().getPlugin(name) != null) detected.add(name);
        }
        if (polarLoaderAvailable || PolarHook.apiPresent()) detected.add(PolarHook.HOOK_NAME);
        return detected;
    }

    private void stopRecorder() {
        SpigotReplays running = detachRecorder();
        if (running == null) return;
        stoppingRecorders.add(running);
        try {
            context.getScheduler().runAsync(() -> finishStopping(running));
        } catch (RuntimeException notScheduled) {
            finishStopping(running);
        }
    }

    private void finishStopping(SpigotReplays stopping) {
        try {
            stopping.awaitShutdown();
        } finally {
            stoppingRecorders.remove(stopping);
        }
    }

    private void awaitPendingStops() {
        for (SpigotReplays stopping : new ArrayList<>(stoppingRecorders)) finishStopping(stopping);
    }

    private @Nullable SpigotReplays detachRecorder() {
        SpigotReplays running = recorder;
        recorder = null;
        replays.use(null);
        if (running != null) running.detach();
        return running;
    }

    @Override
    protected void registerPlatformEvents() {
        cleanupTask = context.getScheduler().runTimerAsync(() -> replayFiles.sweep(System.currentTimeMillis()),
                LocalReplayFiles.SWEEP_DELAY_SECONDS, LocalReplayFiles.SWEEP_PERIOD_SECONDS, TimeUnit.SECONDS);
        Bukkit.getPluginManager().registerEvents(this, plugin);
        for (Player online : Bukkit.getOnlinePlayers()) {
            playerJoined(online.getUniqueId());
        }
    }

    @Override
    protected void registerServerCommand(FeaturesLocaleManager localeManager) {
        PluginCommand command = plugin.getCommand("modlserver");
        if (command == null) {
            pluginLogger.warning("[server-features] /modlserver is missing from plugin.yml, reload command unavailable");
            return;
        }
        ModlServerCommand executor = new ModlServerCommand(
                new FeaturesReloadPresenter(localeManager, this::reload));
        command.setExecutor(executor);
        command.setTabCompleter(executor);
    }

    @Override
    protected void registerProxyCommand(CoreLink coreLink, FeaturesLocaleManager localeManager) {
        PluginCommand command = plugin.getCommand("proxycmd");
        if (command == null) {
            pluginLogger.warning("[server-features] /proxycmd is missing from plugin.yml, proxy command unavailable");
            return;
        }
        command.setExecutor(new ProxyCmdCommand(localeManager, pluginLogger, context.getScheduler(), coreLink));
    }

    @Override
    protected void onDisable() {
        SpigotReplays running = detachRecorder();
        if (running != null) running.awaitShutdown();
        awaitPendingStops();
        FeatureTask cleanup = cleanupTask;
        cleanupTask = null;
        if (cleanup != null) context.getScheduler().cancelTask(cleanup);
        closeReplayUploader();
        StaffModeHandler staffMode = staffModeHandler;
        if (staffMode != null) staffMode.shutdown();
        if (context.getScheduler() instanceof SpigotFeatureScheduler) {
            ((SpigotFeatureScheduler) context.getScheduler()).shutdown();
        }
    }

    private void replaceReplayUploader(@Nullable ModlBackendReplayUploader next) {
        ModlBackendReplayUploader previous = swapReplayUploader(next);
        if (previous != null) previous.close();
    }

    private void closeReplayUploader() {
        ModlBackendReplayUploader previous = swapReplayUploader(null);
        if (previous != null) previous.awaitClosed(UPLOAD_SHUTDOWN_GRACE_SECONDS, TimeUnit.SECONDS);
    }

    private synchronized @Nullable ModlBackendReplayUploader swapReplayUploader(@Nullable ModlBackendReplayUploader next) {
        ModlBackendReplayUploader previous = replayUploader;
        replayUploader = next;
        return previous;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void trackJoin(PlayerJoinEvent event) {
        playerJoined(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void trackQuit(PlayerQuitEvent event) {
        playerLeft(event.getPlayer().getUniqueId());
    }
}
