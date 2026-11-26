package gg.modl.minecraft.spigot.features;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.PacketEventsAPI;
import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.github.retrooper.packetevents.protocol.player.User;
import gg.modl.minecraft.core.feature.FeatureSettings;
import gg.modl.minecraft.core.service.ActiveRecording;
import gg.modl.minecraft.core.service.RecordingOrigin;
import gg.modl.minecraft.core.service.ReplayCaptureResult;
import gg.modl.minecraft.core.service.ReplayCaptureStatus;
import gg.modl.minecraft.core.service.ReplayMemory;
import gg.modl.minecraft.core.service.ReplayRecordingOutcome;
import gg.modl.minecraft.core.service.ReplayRecordingResult;
import gg.modl.minecraft.core.service.ReplayService;
import gg.modl.minecraft.core.util.PluginLogger;
import gg.modl.minecraft.core.util.PublicMethods;
import gg.modl.minecraft.features.FeatureScheduler;
import gg.modl.minecraft.features.reporter.ModlBackendReplayUploader;
import gg.modl.minecraft.replay.recording.RadiusChunks;
import gg.modl.minecraft.replay.recording.RecorderSettings;
import gg.modl.minecraft.replay.recording.RecordingCause;
import gg.modl.minecraft.replay.recording.RecordingSeed;
import gg.modl.minecraft.replay.recording.RecordingStatus;
import gg.modl.minecraft.replay.recording.ReplayRecorder;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

final class SpigotReplays implements ReplayService, Listener {
    private static final int CHUNKS_PER_TICK = 9;
    private static final int SERVER_CHUNKS_PER_TICK = 16;
    private static final String CAPTURE_REASON = "capture";
    private static final long JOIN_SEED_DELAY_TICKS = 20L;
    private static final ClassValue<Optional<Method>> HARDCORE = new ClassValue<Optional<Method>>() {
        @Override
        protected Optional<Method> computeValue(Class<?> type) {
            return PublicMethods.withoutParameters(type, "isHardcore").stream().findFirst();
        }
    };

    private volatile FeatureSettings settings;
    private final FeatureScheduler scheduler;
    private final PluginLogger logger;
    private final ReplayDelivery delivery;
    private final NearbyEntitySeeder nearby;
    private final ReplayRecorder recorder;
    private final ChunkRefreshLimiter refreshes = new ChunkRefreshLimiter(SERVER_CHUNKS_PER_TICK);

    private SpigotReplays(JavaPlugin plugin, FeatureSettings settings, FeatureScheduler scheduler, PluginLogger logger,
                          Supplier<@Nullable ModlBackendReplayUploader> uploader, String minecraftVersion, LocalReplayFiles files) {
        this.settings = settings;
        this.scheduler = scheduler;
        this.logger = logger;
        this.delivery = new ReplayDelivery(uploader, minecraftVersion, logger);
        this.nearby = new NearbyEntitySeeder(new PacketEventsEntityStates(SpigotTrackingRanges.ofServer()), logger);
        this.recorder = new ReplayRecorder(RecorderSettings.builder()
                .windowSeconds(settings.getWindowSeconds())
                .radiusBlocks(settings.getRadiusBlocks())
                .memoryBudgetBytes(settings.memoryBudgetBytes())
                .minecraftVersion(minecraftVersion)
                .dataVersion(dataVersion())
                .serverSoftware(Bukkit.getName() + " " + Bukkit.getVersion())
                .recorderVersion(plugin.getDescription().getVersion())
                .build(), files, plugin.getLogger());
        this.recorder.recordEveryPlayer(settings.recordsEveryPlayer());
        this.recorder.open();
    }

    static boolean packetEventsReady() {
        try {
            Class.forName("com.github.retrooper.packetevents.PacketEvents");
        } catch (ClassNotFoundException e) {
            return false;
        }
        PacketEventsAPI<?> api = PacketEvents.getAPI();
        return api != null && api.isInitialized();
    }

    static SpigotReplays start(JavaPlugin plugin, FeatureSettings settings, FeatureScheduler scheduler, PluginLogger logger,
                               Supplier<@Nullable ModlBackendReplayUploader> uploader, String minecraftVersion,
                               LocalReplayFiles files) {
        SpigotReplays replays = new SpigotReplays(plugin, settings, scheduler, logger, uploader, minecraftVersion, files);
        Bukkit.getPluginManager().registerEvents(replays, plugin);
        if (settings.recordsEveryPlayer()) replays.recordOnlinePlayers();
        return replays;
    }

    void retune(FeatureSettings next) {
        FeatureSettings previous = settings;
        settings = next;
        recorder.recordEveryPlayer(next.recordsEveryPlayer());
        if (!previous.sameRecorderLimits(next)) {
            recorder.retune(next.getWindowSeconds(), next.getRadiusBlocks(), next.memoryBudgetBytes());
        }
        if (!previous.recordsEveryPlayer() && next.recordsEveryPlayer()) recordOnlinePlayers();
    }

    void detach() {
        HandlerList.unregisterAll(this);
        recorder.detach();
    }

    void awaitShutdown() {
        recorder.awaitShutdown();
    }

    private void recordOnlinePlayers() {
        for (Player online : Bukkit.getOnlinePlayers()) {
            UUID uuid = online.getUniqueId();
            scheduler.runForPlayer(uuid, () -> startMidSession(uuid, RecordingCause.AUTO_RECORD));
        }
    }

    @Override
    public CompletableFuture<ReplayCaptureResult> captureReplayResult(UUID targetUuid, String targetName) {
        if (!recorder.isRecording(targetUuid)) return CompletableFuture.completedFuture(ReplayCaptureResult.noActiveRecording());
        if (!delivery.available()) {
            logger.warning("[replay] The replay of " + targetName + " was not captured: the replay uploader is not connected");
            return CompletableFuture.completedFuture(ReplayCaptureResult.error());
        }
        return recorder.capture(targetUuid, CAPTURE_REASON).thenCompose(capture -> delivery.deliver(capture, targetUuid, targetName));
    }

    @Override
    public ReplayCaptureStatus getReplayStatus(UUID playerUuid) {
        return recorder.isRecording(playerUuid) ? ReplayCaptureStatus.OK : ReplayCaptureStatus.NO_ACTIVE_RECORDING;
    }

    @Override
    public CompletableFuture<ReplayRecordingResult> startRecording(UUID targetUuid, String targetName) {
        return onPlayerThread(targetUuid, () -> {
            ReplayRecordingOutcome outcome = startMidSession(targetUuid, RecordingCause.STAFF);
            return ReplayRecordingResult.of(outcome, activeRecording(targetUuid), memory());
        });
    }

    @Override
    public CompletableFuture<ReplayRecordingResult> stopRecording(UUID targetUuid, String targetName) {
        return onPlayerThread(targetUuid, () -> {
            if (recorder.stopIfStartedBy(targetUuid, RecordingCause.STAFF)) {
                return ReplayRecordingResult.of(ReplayRecordingOutcome.OK, null, memory());
            }
            ActiveRecording kept = activeRecording(targetUuid);
            ReplayRecordingOutcome outcome = kept == null ? ReplayRecordingOutcome.NOT_RECORDING : ReplayRecordingOutcome.NOT_STARTED_BY_STAFF;
            return ReplayRecordingResult.of(outcome, kept, memory());
        });
    }

    @Override
    public CompletableFuture<ReplayRecordingResult> recordingStatus(UUID targetUuid, String targetName) {
        return CompletableFuture.completedFuture(ReplayRecordingResult.of(ReplayRecordingOutcome.OK, activeRecording(targetUuid), memory()));
    }

    @Override
    public void recordOnSuspicion(UUID playerUuid) {
        if (!settings.startsRecordingAtFlags() || recorder.keepForFlag(playerUuid)) return;
        scheduler.runForPlayer(playerUuid, () -> startMidSession(playerUuid, RecordingCause.ANTICHEAT_FLAG));
    }

    @Override
    public void onPlayerDisconnect(UUID playerUuid) {
        recorder.stop(playerUuid);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        recorder.start(uuid, event.getPlayer().getName());
        if (!recorder.isRecording(uuid)) return;
        scheduler.runForPlayerLater(uuid, () -> {
            Player player = Bukkit.getPlayer(uuid);
            RecordingSeed seed = player == null ? null : recorder.seedIfUnplaced(uuid);
            if (player != null && seed != null) seedTarget(seed, player);
        }, JOIN_SEED_DELAY_TICKS);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        recorder.stop(event.getPlayer().getUniqueId());
    }

    private CompletableFuture<ReplayRecordingResult> onPlayerThread(UUID uuid, Supplier<ReplayRecordingResult> task) {
        CompletableFuture<ReplayRecordingResult> result = new CompletableFuture<>();
        try {
            scheduler.runForPlayer(uuid, () -> result.complete(changeRecording(uuid, task)),
                    () -> result.complete(ReplayRecordingResult.of(ReplayRecordingOutcome.NOT_LOCAL)));
        } catch (RuntimeException e) {
            logger.warning("[replay] Could not schedule the recording change of " + uuid + ": " + e.getMessage());
            result.complete(ReplayRecordingResult.of(ReplayRecordingOutcome.ERROR));
        }
        return result;
    }

    private ReplayRecordingResult changeRecording(UUID uuid, Supplier<ReplayRecordingResult> task) {
        try {
            return task.get();
        } catch (RuntimeException e) {
            logger.warning("[replay] Could not change the recording of " + uuid + ": " + e.getMessage());
            return ReplayRecordingResult.of(ReplayRecordingOutcome.ERROR);
        }
    }

    private @Nullable ActiveRecording activeRecording(UUID uuid) {
        return recorder.status(uuid).map(SpigotReplays::activeRecording).orElse(null);
    }

    private static ActiveRecording activeRecording(RecordingStatus status) {
        return new ActiveRecording(originOf(status.getCause()), status.getRecordingMillis());
    }

    private static RecordingOrigin originOf(RecordingCause cause) {
        switch (cause) {
            case ANTICHEAT_FLAG:
                return RecordingOrigin.ANTICHEAT_FLAG;
            case STAFF:
                return RecordingOrigin.STAFF;
            default:
                return RecordingOrigin.AUTO_RECORD;
        }
    }

    private ReplayMemory memory() {
        return new ReplayMemory(recorder.usedBytes(), recorder.limitBytes(), recorder.activeRecordings());
    }

    private ReplayRecordingOutcome startMidSession(UUID uuid, RecordingCause cause) {
        Player player = Bukkit.getPlayer(uuid);
        if (player == null || !player.isOnline()) return ReplayRecordingOutcome.NOT_LOCAL;
        RecordingSeed seed = recorder.startSeeded(uuid, player.getName(), cause);
        if (seed == null) {
            if (cause == RecordingCause.ANTICHEAT_FLAG) recorder.keepForFlag(uuid);
            return recorder.isRecording(uuid) ? ReplayRecordingOutcome.ALREADY_RECORDING : ReplayRecordingOutcome.NOT_AVAILABLE;
        }
        seedTarget(seed, player);
        seedProfile(seed, player);
        nearby.seed(seed, player, settings.getRadiusBlocks());
        if (cause == RecordingCause.STAFF) refreshChunks(player);
        return ReplayRecordingOutcome.OK;
    }

    @SuppressWarnings("deprecation")
    private void seedTarget(RecordingSeed seed, Player player) {
        World world = player.getWorld();
        User user = PacketEvents.getAPI().getPlayerManager().getUser(player);
        seed.world(worldKey(world), world.getName(), user, player.getEntityId());
        Location location = player.getLocation();
        seed.position(location.getX(), location.getY(), location.getZ(), location.getYaw(), location.getPitch(), player.isOnGround());
        seed.status((float) player.getHealth(), player.getFoodLevel(), player.getSaturation(), (float) player.getMaxHealth(),
                player.getLevel(), player.getExp(), player.getTotalExperience(), player.getInventory().getHeldItemSlot(),
                ReplayGameMode.of(player.getGameMode()), hardcore(world));
        seed.inventory(menuSlots(player.getInventory()));
    }

    private static void seedProfile(RecordingSeed seed, Player player) {
        User user = PacketEvents.getAPI().getPlayerManager().getUser(player);
        seed.targetProfile(user == null ? null : user.getProfile(), ReplayGameMode.of(player.getGameMode()));
    }

    private void refreshChunks(Player player) {
        World world = player.getWorld();
        int centerX = player.getLocation().getBlockX() >> 4;
        int centerZ = player.getLocation().getBlockZ() >> 4;
        int radius = RadiusChunks.of(settings.getRadiusBlocks());
        List<int[]> chunks = new ArrayList<>();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) chunks.add(new int[]{centerX + dx, centerZ + dz, dx * dx + dz * dz});
        }
        chunks.sort((a, b) -> Integer.compare(a[2], b[2]));
        refreshBatch(player.getUniqueId(), world, chunks, 0);
    }

    @SuppressWarnings("deprecation")
    private void refreshBatch(UUID uuid, World world, List<int[]> chunks, int from) {
        if (from >= chunks.size() || !recorder.isRecording(uuid)) return;
        int to = from + refreshes.allowance(Math.min(CHUNKS_PER_TICK, chunks.size() - from));
        long now = System.currentTimeMillis();
        for (int i = from; i < to; i++) {
            int[] chunk = chunks.get(i);
            if (world.isChunkLoaded(chunk[0], chunk[1]) && refreshes.firstRecently(world.getName(), chunk[0], chunk[1], now)) {
                world.refreshChunk(chunk[0], chunk[1]);
            }
        }
        if (to < chunks.size()) scheduler.runForPlayerLater(uuid, () -> refreshBatch(uuid, world, chunks, to), 1L);
    }

    private static Map<Integer, ItemStack> menuSlots(PlayerInventory inventory) {
        Map<Integer, ItemStack> slots = new HashMap<>();
        org.bukkit.inventory.ItemStack[] contents = inventory.getContents();
        for (int i = 0; i < contents.length; i++) addSlot(slots, toProtocolSlot(i), contents[i]);
        org.bukkit.inventory.ItemStack[] armor = inventory.getArmorContents();
        for (int i = 0; i < armor.length && i < 4; i++) addSlot(slots, 8 - i, armor[i]);
        return slots;
    }

    private static void addSlot(Map<Integer, ItemStack> slots, int slot, org.bukkit.inventory.@Nullable ItemStack item) {
        if (slot < 0 || item == null || item.getType() == Material.AIR) return;
        slots.put(slot, SpigotConversionUtil.fromBukkitItemStack(item));
    }

    static int toProtocolSlot(int bukkitSlot) {
        if (bukkitSlot >= 0 && bukkitSlot <= 8) return 36 + bukkitSlot;
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

    private static boolean hardcore(World world) {
        Optional<Method> probe = HARDCORE.get(world.getClass());
        if (!probe.isPresent()) return false;
        try {
            Object value = probe.get().invoke(world);
            return Boolean.TRUE.equals(value);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    static String worldKey(World world) {
        switch (world.getEnvironment()) {
            case NETHER: return "minecraft:the_nether";
            case THE_END: return "minecraft:the_end";
            default: return "minecraft:overworld";
        }
    }

    @SuppressWarnings("deprecation")
    private static int dataVersion() {
        try {
            Object unsafe = Bukkit.getUnsafe();
            Optional<Method> method = PublicMethods.withoutParameters(unsafe.getClass(), "getDataVersion").stream().findFirst();
            if (!method.isPresent()) return 0;
            Object value = method.get().invoke(unsafe);
            return value instanceof Integer ? (Integer) value : 0;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return 0;
        }
    }
}
