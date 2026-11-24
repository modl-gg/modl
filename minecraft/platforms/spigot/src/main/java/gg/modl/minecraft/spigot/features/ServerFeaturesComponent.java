package gg.modl.minecraft.spigot.features;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState;
import gg.modl.minecraft.core.connection.BackendConnections;
import gg.modl.minecraft.core.network.MemberActionSender;
import gg.modl.minecraft.core.service.ReplayCaptureResult;
import gg.modl.minecraft.core.service.ReplayCaptureStatus;
import gg.modl.minecraft.core.service.ReplayService;
import gg.modl.minecraft.core.util.PluginLogger;
import gg.modl.minecraft.core.util.PublicMethods;
import gg.modl.minecraft.features.AbstractServerFeatures;
import gg.modl.minecraft.features.FeatureTask;
import gg.modl.minecraft.features.FeaturesReloadPresenter;
import gg.modl.minecraft.features.config.ServerFeaturesConfig;
import gg.modl.minecraft.features.config.StaffModeConfig;
import gg.modl.minecraft.features.link.CoreLink;
import gg.modl.minecraft.features.locale.FeaturesLocaleManager;
import gg.modl.minecraft.features.moderation.FeatureModerationOps;
import gg.modl.minecraft.features.moderation.LocalModerationOps;
import gg.modl.minecraft.features.reporter.AutoReporter;
import gg.modl.minecraft.features.reporter.ModlBackendReplayUploader;
import gg.modl.minecraft.features.reporter.hook.AntiCheatHook;
import gg.modl.minecraft.replay.api.ReplayMetadata;
import gg.modl.minecraft.replay.format.events.BlockChangeEvent;
import gg.modl.minecraft.replay.recording.PacketRecorder;
import gg.modl.minecraft.replay.recording.RecordingConfig;
import gg.modl.minecraft.replay.recording.RecordingManager;
import gg.modl.minecraft.spigot.features.command.ModlServerCommand;
import gg.modl.minecraft.spigot.features.command.ProxyCmdCommand;
import gg.modl.minecraft.spigot.features.handler.FreezeHandler;
import gg.modl.minecraft.spigot.features.handler.MemberFreezeChatGuard;
import gg.modl.minecraft.spigot.features.handler.StaffModeHandler;
import gg.modl.minecraft.spigot.features.reporter.hook.GrimHook;
import gg.modl.minecraft.spigot.features.reporter.hook.PolarHook;
import gg.modl.minecraft.spigot.features.reporter.hook.VulcanHook;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.material.MaterialData;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.Nullable;

import java.io.File;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Logger;

public class ServerFeaturesComponent extends AbstractServerFeatures implements Listener {
    private static final ClassValue<Optional<Method>> BLOCK_DATA_ACCESSORS = new ClassValue<Optional<Method>>() {
        @Override
        protected Optional<Method> computeValue(Class<?> blockType) {
            return PublicMethods.withoutParameters(blockType, "getBlockData").stream().findFirst();
        }
    };
    private static final long MILLIS_PER_MINUTE = 60_000L;
    private static final long REPLAY_CLEANUP_INTERVAL_MINUTES = 5L;

    private final JavaPlugin plugin;
    private final boolean polarLoaderAvailable;
    private @Nullable StaffModeHandler staffModeHandler;

    private @Nullable RecordingManager recordingManager;
    private @Nullable PacketRecorder packetRecorder;
    private volatile @Nullable ModlBackendReplayUploader replayUploader;
    private @Nullable FeatureTask replayCleanupTask;
    private final Map<UUID, Integer> worldChangeGeneration = new ConcurrentHashMap<>();

    public ServerFeaturesComponent(JavaPlugin plugin, ServerFeaturesConfig featuresConfig, PluginLogger logger,
                                   boolean polarLoaderAvailable) {
        super(new SpigotFeaturesContext(plugin), featuresConfig, logger);
        this.plugin = plugin;
        this.polarLoaderAvailable = polarLoaderAvailable;
    }

    public void uploadReplaysTo(BackendConnections connections) {
        replaceReplayUploader(ModlBackendReplayUploader.direct(connections));
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
        if (Bukkit.getPluginManager().getPlugin("GrimAC") != null) {
            GrimHook grimHook = new GrimHook(plugin, featuresConfig, violationTracker, autoReporter);
            grimHook.register();
            hooks.add(grimHook);
        }

        if (Bukkit.getPluginManager().getPlugin("Vulcan") != null) {
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
        }
    }

    @Override
    protected @Nullable ReplayService initReplayRecording(ServerFeaturesConfig config) {
        if (!config.isReplayEnabled()) {
            return null;
        }

        try {
            Class.forName("com.github.retrooper.packetevents.PacketEvents");
        } catch (ClassNotFoundException e) {
            pluginLogger.warning("[replay] PacketEvents not found, replay recording disabled");
            return null;
        }

        if (PacketEvents.getAPI() == null) {
            pluginLogger.warning("[replay] PacketEvents not initialized, replay recording disabled");
            return null;
        }

        RecordingConfig recordingConfig = new RecordingConfig() {
            @Override public int bufferDurationSeconds() { return config.getReplayBufferDuration(); }
            @Override public int maxDurationSeconds() { return config.getReplayMaxDuration(); }
            @Override public int radiusBlocks() { return config.getReplayRadius(); }
            @Override public int moveThrottleMs() { return config.getReplayMoveThrottle(); }
            @Override public String mcVersion() { return context.getMinecraftVersion(); }
        };

        File replaysDir = new File(plugin.getDataFolder(), "replays");

        RecordingManager recordingManager = new RecordingManager(recordingConfig, replaysDir, plugin.getLogger());
        PacketRecorder packetRecorder = new PacketRecorder(recordingManager, recordingConfig, plugin.getLogger());
        recordingManager.setPacketRecorder(packetRecorder);
        packetRecorder.register();
        this.recordingManager = recordingManager;
        this.packetRecorder = packetRecorder;

        ReplayService replayService = new ReplayService() {
            @Override
            public CompletableFuture<ReplayCaptureResult> captureReplayResult(UUID targetUuid, String targetName) {
                if (!recordingManager.isRecording(targetUuid)) {
                    return CompletableFuture.completedFuture(ReplayCaptureResult.noActiveRecording());
                }

                packetRecorder.cleanupPlayer(targetUuid);

                Player localPlayer = Bukkit.getPlayer(targetUuid);
                String resolvedName = localPlayer != null ? localPlayer.getName() : targetName;

                ModlBackendReplayUploader uploaderSnapshot = replayUploader;
                return recordingManager.stopRecordingAsync(targetUuid)
                        .thenCompose(metadata -> {
                            if (config.isReplayAutoRecord()) {
                                context.getScheduler().runForPlayerLater(targetUuid, () -> {
                                    Player player = Bukkit.getPlayer(targetUuid);
                                    if (player != null && player.isOnline()) {
                                        startRecordingForPlayer(player);
                                    }
                                }, 40L);
                            }
                            return uploadAndCleanupReplay(uploaderSnapshot, targetUuid, resolvedName, metadata);
                        });
            }

            @Override
            public ReplayCaptureStatus getReplayStatus(UUID playerUuid) {
                return recordingManager.isRecording(playerUuid)
                        ? ReplayCaptureStatus.OK
                        : ReplayCaptureStatus.NO_ACTIVE_RECORDING;
            }
        };

        if (config.isReplayAutoRecord()) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                startRecordingForPlayer(player);
            }
        }

        replayCleanupTask = context.getScheduler().runTimerAsync(
                () -> LocalReplayFiles.deleteOlderThan(replaysDir, config.getReplayLocalTtl() * MILLIS_PER_MINUTE,
                        System.currentTimeMillis()),
                REPLAY_CLEANUP_INTERVAL_MINUTES, REPLAY_CLEANUP_INTERVAL_MINUTES, TimeUnit.MINUTES);
        return replayService;
    }

    @Override
    protected void registerPlatformEvents() {
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
        FeatureTask cleanupTask = replayCleanupTask;
        if (cleanupTask != null) cleanupTask.cancel();
        RecordingManager manager = recordingManager;
        if (manager != null) manager.stopAll();
        PacketRecorder recorder = packetRecorder;
        if (recorder != null) recorder.unregister();
        closeReplayUploader();
        StaffModeHandler staffMode = staffModeHandler;
        if (staffMode != null) staffMode.shutdown();
        if (context.getScheduler() instanceof SpigotFeatureScheduler) {
            ((SpigotFeatureScheduler) context.getScheduler()).shutdown();
        }
    }

    private synchronized void replaceReplayUploader(@Nullable ModlBackendReplayUploader next) {
        ModlBackendReplayUploader previous = replayUploader;
        replayUploader = next;
        if (previous != null) previous.close();
    }

    private void closeReplayUploader() {
        replaceReplayUploader(null);
    }

    private void startRecordingForPlayer(Player player) {
        RecordingManager manager = recordingManager;
        PacketRecorder recorder = packetRecorder;
        if (manager == null || recorder == null) return;
        if (manager.isRecording(player.getUniqueId())) return;

        Location loc = player.getLocation();
        int radius = featuresConfig.getReplayRadius();

        recorder.trackSelf(player.getUniqueId(), player.getName(), player.getEntityId(),
                loc.getX(), loc.getY(), loc.getZ(), loc.getYaw(), loc.getPitch());

        double radiusSq = (double) radius * radius;
        for (Player nearby : Bukkit.getOnlinePlayers()) {
            if (nearby.equals(player)) continue;
            Location nLoc = nearby.getLocation();
            if (!nLoc.getWorld().equals(loc.getWorld())) continue;
            if (nLoc.distanceSquared(loc) > radiusSq) continue;
            recorder.getEntityTracker().trackPlayer(
                    player.getUniqueId(), nearby.getEntityId(), nearby.getUniqueId(), nearby.getName(),
                    nLoc.getX(), nLoc.getY(), nLoc.getZ(),
                    nLoc.getYaw(), nLoc.getPitch());
        }

        manager.startRecording(
                player.getUniqueId(), player.getName(),
                loc.getBlockX(), loc.getBlockY(), loc.getBlockZ()
        );

        seedInventoryFromBukkit(recorder, player);
        recorder.emitInitialSelfEquipment(player.getUniqueId());
    }

    private static void seedInventoryFromBukkit(PacketRecorder recorder, Player player) {
        PlayerInventory inv = player.getInventory();
        Map<Integer, String> protocolSlots = new HashMap<>();
        Map<Integer, Integer> protocolCounts = new HashMap<>();

        ItemStack[] contents = inv.getContents();
        for (int i = 0; i < contents.length && i <= 40; i++) {
            int protocolSlot = toProtocolSlot(i);
            if (protocolSlot < 0) continue;

            ItemStack item = contents[i];
            boolean present = item != null && item.getType() != Material.AIR;
            protocolSlots.put(protocolSlot, present ? item.getType().name().toLowerCase(Locale.ROOT) : "air");
            protocolCounts.put(protocolSlot, present ? item.getAmount() : 0);
        }

        recorder.seedInventoryCache(player.getUniqueId(), protocolSlots, protocolCounts);
        recorder.seedHeldSlot(player.getUniqueId(), inv.getHeldItemSlot());
    }

    static int toProtocolSlot(int bukkitSlot) {
        if (bukkitSlot <= 8) return 36 + bukkitSlot;
        if (bukkitSlot <= 35) return bukkitSlot;
        switch (bukkitSlot) {
            case 36: return 8;
            case 37: return 7;
            case 38: return 6;
            case 39: return 5;
            case 40: return 45;
            default: return -1;
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (featuresConfig.isReplayEnabled() && featuresConfig.isReplayAutoRecord() && recordingManager != null) {
            context.getScheduler().runForPlayerLater(event.getPlayer().getUniqueId(), () -> {
                if (event.getPlayer().isOnline()) {
                    startRecordingForPlayer(event.getPlayer());
                }
            }, 40L);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void trackJoin(PlayerJoinEvent event) {
        playerJoined(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void trackQuit(PlayerQuitEvent event) {
        playerLeft(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onPlayerChangedWorld(PlayerChangedWorldEvent event) {
        if (!featuresConfig.isReplayEnabled() || !featuresConfig.isReplayAutoRecord()) return;
        RecordingManager manager = recordingManager;
        PacketRecorder recorder = packetRecorder;
        if (manager == null || recorder == null) return;

        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();

        if (manager.isRecording(playerId)) {
            recorder.cleanupPlayer(playerId);
            manager.discardRecording(playerId);
        }

        recorder.getEntityTracker().clearPlayer(playerId);

        int generation = worldChangeGeneration.merge(playerId, 1, Integer::sum);

        context.getScheduler().runForPlayerLater(playerId, () -> {
            Integer current = worldChangeGeneration.get(playerId);
            if (current == null || current != generation) return;
            worldChangeGeneration.remove(playerId);

            if (player.isOnline() && !manager.isRecording(playerId)) {
                startRecordingForPlayer(player);
            }
        }, 40L);
    }

    @SuppressWarnings("deprecation")
    static int resolveBlockStateId(Block block, Function<Object, Integer> modernResolver,
                                   BiFunction<Material, Byte, Integer> legacyResolver) {
        Optional<Method> getBlockData = BLOCK_DATA_ACCESSORS.get(block.getClass());
        if (getBlockData.isPresent()) {
            try {
                Object blockData = getBlockData.get().invoke(block);
                if (blockData != null) return modernResolver.apply(blockData);
            } catch (ReflectiveOperationException | RuntimeException ignored) {}
        }

        return legacyResolver.apply(block.getType(), block.getData());
    }

    static int resolveBlockStateId(Block block) {
        return resolveBlockStateId(block, ServerFeaturesComponent::resolveModernBlockStateId, ServerFeaturesComponent::resolveLegacyBlockStateId);
    }

    private CompletableFuture<ReplayCaptureResult> uploadAndCleanupReplay(
            @Nullable ModlBackendReplayUploader uploader, UUID playerId, String playerName,
            @Nullable ReplayMetadata metadata) {
        File replayFile = metadata != null ? metadata.getOutputFile() : null;
        if (replayFile == null || !replayFile.exists()) {
            pluginLogger.warning("[replay] No replay file found for " + playerName + " after stopping recording");
            return CompletableFuture.completedFuture(ReplayCaptureResult.error());
        }
        if (uploader == null) {
            pluginLogger.warning("[replay] Replay uploader unavailable; keeping local replay file for " + playerName);
            return CompletableFuture.completedFuture(ReplayCaptureResult.error());
        }

        try {
            return uploader.uploadAsync(replayFile, context.getMinecraftVersion(), playerId, playerName)
                    .thenApply(ReplayCaptureResult::ok)
                    .whenComplete((result, ex) -> {
                        if (ex != null) {
                            pluginLogger.warning("[replay] Replay upload failed for " + playerName + ": " + ex.getMessage());
                        }
                        cleanupReplayFileAfterUpload(replayFile, featuresConfig.isReplaySaveLocal(), result, ex, plugin.getLogger());
                    });
        } catch (RejectedExecutionException e) {
            pluginLogger.warning("[replay] Replay uploader closed; keeping local replay file for " + playerName);
            return CompletableFuture.completedFuture(ReplayCaptureResult.error());
        }
    }

    static void cleanupReplayFileAfterUpload(File replayFile, boolean saveLocal,
                                             @Nullable ReplayCaptureResult uploadResult,
                                             @Nullable Throwable uploadFailure,
                                             Logger logger) {
        if (saveLocal || uploadFailure != null || uploadResult == null
                || uploadResult.getStatus() != ReplayCaptureStatus.OK) {
            return;
        }
        if (!replayFile.delete()) {
            logger.warning("[replay] Failed to delete replay file " + replayFile.getAbsolutePath());
        }
    }

    static int recordingDeltaMs(long nowMs, long recordingStartMs) {
        long elapsedMs = nowMs - recordingStartMs;
        if (elapsedMs <= 0L) {
            return 0;
        }
        if (elapsedMs >= Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        return (int) elapsedMs;
    }

    private static int recordingDeltaMs(PacketRecorder recorder, UUID playerId) {
        return recordingDeltaMs(System.currentTimeMillis(), recorder.getRecordingStartTime(playerId));
    }

    private static int resolveModernBlockStateId(Object blockData) {
        try {
            Class<?> blockDataClass = Class.forName("org.bukkit.block.data.BlockData");
            Method converter = SpigotConversionUtil.class.getMethod("fromBukkitBlockData", blockDataClass);
            return ((WrappedBlockState) converter.invoke(null, blockData)).getGlobalId();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to convert Bukkit BlockData to PacketEvents state", e);
        }
    }

    @SuppressWarnings("deprecation")
    private static int resolveLegacyBlockStateId(Material material, byte data) {
        return SpigotConversionUtil.fromBukkitMaterialData(new MaterialData(material, data)).getGlobalId();
    }

    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event) {
        recordBlockChange(event.getPlayer(), event.getBlockPlaced());
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        recordBlockChange(event.getPlayer(), event.getBlock());
    }

    private void recordBlockChange(Player player, Block block) {
        RecordingManager manager = recordingManager;
        PacketRecorder recorder = packetRecorder;
        if (manager == null || recorder == null) return;
        UUID playerId = player.getUniqueId();
        if (!manager.isRecording(playerId)) return;
        int stateId = resolveBlockStateId(block);
        manager.enqueueEvent(playerId, new BlockChangeEvent(recordingDeltaMs(recorder, playerId), block.getX(),
                (short) block.getY(), block.getZ(), stateId));
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        worldChangeGeneration.remove(playerId);

        RecordingManager manager = recordingManager;
        if (manager != null) manager.discardRecording(playerId);
        PacketRecorder recorder = packetRecorder;
        if (recorder != null) recorder.disconnectPlayer(playerId);
    }
}
