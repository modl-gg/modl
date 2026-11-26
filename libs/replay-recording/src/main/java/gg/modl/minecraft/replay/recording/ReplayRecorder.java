package gg.modl.minecraft.replay.recording;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import gg.modl.minecraft.replay.format.ReplayFileFormat;
import org.jspecify.annotations.Nullable;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class ReplayRecorder implements AutoCloseable {
    static final int MAX_PENDING_CAPTURES = 2;
    private static final long MAINTENANCE_PERIOD_MS = 1_000L;
    private static final long SHUTDOWN_DEADLINE_MS = 2_000L;
    private static final String PARTIAL_EXTENSION = ".partial";

    private volatile RecorderSettings settings;
    private final ReplayOutput output;
    private final Logger logger;
    private final Map<UUID, Recording> recordings = new ConcurrentHashMap<>();
    private final Object admission = new Object();
    private boolean budgetFullNoticed;
    private final MemoryBudget budget;
    private final NameTable names = new NameTable();
    private final TranslationContext context;
    private final ReplayComposer composer;
    private final DecodeFailures failures;
    private final DeflaterPool deflaters;
    private final ThreadPoolExecutor ingest;
    private final ThreadPoolExecutor writer;
    private final ScheduledExecutorService maintenance;
    private final PacketTap tap;
    private final Map<Long, CompletableFuture<ReplayCapture>> pendingCaptures = new ConcurrentHashMap<>();
    private final AtomicLong nextCapture = new AtomicLong();
    private @Nullable ScheduledFuture<?> maintenanceTask;
    private volatile boolean closed;

    public ReplayRecorder(RecorderSettings settings, ReplayOutput output, Logger logger) {
        this.settings = settings;
        this.output = output;
        this.logger = logger;
        this.budget = new MemoryBudget(settings.getMemoryBudgetBytes());
        ServerVersion serverVersion = PacketEvents.getAPI().getServerManager().getVersion();
        this.context = new TranslationContext(serverVersion, names);
        this.failures = new DecodeFailures(logger);
        this.composer = new ReplayComposer(settings, serverVersion, names, context.typeName(EntityTypes.FALLING_BLOCK), logger);
        int ingestThreads = Math.max(1, Math.min(2, Runtime.getRuntime().availableProcessors() / 4));
        this.deflaters = new DeflaterPool(ingestThreads);
        this.ingest = new ThreadPoolExecutor(ingestThreads, ingestThreads, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<>(),
                threads("modl-replay-ingest"));
        this.ingest.allowCoreThreadTimeOut(true);
        this.writer = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(MAX_PENDING_CAPTURES),
                threads("modl-replay-writer"));
        this.writer.allowCoreThreadTimeOut(true);
        this.maintenance = Executors.newSingleThreadScheduledExecutor(threads("modl-replay-maintenance"));
        PacketHandlers handlers = new PacketHandlers();
        ChunkIngest chunks = new ChunkIngest(failures, budget, deflaters,
                serverVersion.isNewerThanOrEquals(ServerVersion.V_1_9) && serverVersion.isOlderThan(ServerVersion.V_1_17));
        new EntityPackets(context).register(handlers);
        new WorldPackets(context, chunks).register(handlers);
        new TargetPackets(context).register(handlers);
        new PlayerInfoPackets().register(handlers);
        new ChatPackets().register(handlers);
        this.tap = new PacketTap(recordings, handlers, failures, this::stop);
    }

    public void open() {
        PacketEvents.getAPI().getEventManager().registerListener(tap);
        maintenanceTask = maintenance.scheduleWithFixedDelay(this::maintain, MAINTENANCE_PERIOD_MS, MAINTENANCE_PERIOD_MS, TimeUnit.MILLISECONDS);
    }

    public void retune(int windowSeconds, int radiusBlocks, long memoryBudgetBytes) {
        settings = settings.toBuilder()
                .windowSeconds(windowSeconds)
                .radiusBlocks(radiusBlocks)
                .memoryBudgetBytes(memoryBudgetBytes)
                .build();
        budget.limit(memoryBudgetBytes);
    }

    public boolean isRecording(UUID target) {
        return recordings.containsKey(target);
    }

    public boolean start(UUID target, String name) {
        return startRecording(target, name, RecordingCause.AUTO_RECORD) != null;
    }

    public @Nullable RecordingSeed startSeeded(UUID target, String name, RecordingCause cause) {
        Recording recording = startRecording(target, name, cause);
        return recording == null ? null : new RecordingSeed(recording, context);
    }

    public boolean keepForFlag(UUID target) {
        Recording recording = recordings.get(target);
        return recording != null && recording.keepForFlag();
    }

    public boolean stopIfStartedBy(UUID target, RecordingCause starter) {
        Recording recording = recordings.get(target);
        if (recording == null || !recording.endIfStartedBy(starter)) return false;
        if (recordings.remove(target, recording)) recording.close();
        return true;
    }

    public Optional<RecordingStatus> status(UUID target) {
        Recording recording = recordings.get(target);
        return recording == null ? Optional.empty() : Optional.of(new RecordingStatus(recording.cause(), recording.now()));
    }

    public @Nullable RecordingSeed seedIfUnplaced(UUID target) {
        Recording recording = recordings.get(target);
        return recording == null || recording.currentSegment() >= 0 ? null : new RecordingSeed(recording, context);
    }

    private @Nullable Recording startRecording(UUID target, String name, RecordingCause cause) {
        synchronized (admission) {
            if (closed) return null;
            Recording existing = recordings.get(target);
            if (existing != null) {
                existing.raise(cause);
                return null;
            }
            if (!budget.admits(recordings.size() + 1) && !makeRoomFor(cause)) {
                noticeBudgetFull();
                return null;
            }
            budgetFullNoticed = false;
            Recording created = new Recording(target, name, cause, context.getPlayerType(), budget, ingest,
                    () -> RadiusChunks.of(settings.getRadiusBlocks()), e -> failures.record("replay task", e));
            recordings.put(target, created);
            return created;
        }
    }

    private boolean makeRoomFor(RecordingCause cause) {
        if (!budget.admits(recordings.size())) return false;
        Recording yielding = null;
        for (Recording recording : recordings.values()) {
            RecordingCause held = recording.rank();
            if (!cause.outranks(held)) continue;
            if (yielding == null || yielding.rank().outranks(held)
                    || (yielding.rank() == held && recording.startedBefore(yielding))) {
                yielding = recording;
            }
        }
        if (yielding == null) return false;
        if (recordings.remove(yielding.getTargetUuid(), yielding)) yielding.close();
        return true;
    }

    private void noticeBudgetFull() {
        if (budgetFullNoticed) return;
        budgetFullNoticed = true;
        logger.info("[replay] The replay memory budget is full, so new recordings start only once it has room again");
    }

    public void stop(UUID target) {
        Recording recording = recordings.remove(target);
        if (recording != null) recording.close();
    }

    public CompletableFuture<ReplayCapture> capture(UUID target, String reason) {
        Recording recording = recordings.get(target);
        if (recording == null) return CompletableFuture.completedFuture(ReplayCapture.refused(ReplayCapture.Refusal.NOT_RECORDING));
        CompletableFuture<ReplayCapture> result = new CompletableFuture<>();
        long id = nextCapture.incrementAndGet();
        pendingCaptures.put(id, result);
        if (closed) {
            finish(id, result, ReplayCapture.refused(ReplayCapture.Refusal.STOPPED), null);
            return result;
        }
        try {
            recording.serial().execute(() -> snapshotAndQueue(recording, reason, id, result));
        } catch (RejectedExecutionException e) {
            finish(id, result, ReplayCapture.refused(ReplayCapture.Refusal.STOPPED), null);
        }
        return result;
    }

    private void snapshotAndQueue(Recording recording, String reason, long id, CompletableFuture<ReplayCapture> result) {
        if (closed) {
            finish(id, result, ReplayCapture.refused(ReplayCapture.Refusal.STOPPED), null);
            return;
        }
        LeasedSnapshot leased = null;
        try {
            leased = recording.leaseSnapshot(recording.now());
            if (leased == null) {
                finish(id, result, ReplayCapture.refused(ReplayCapture.Refusal.MEMORY_BUDGET), null);
                return;
            }
            LeasedSnapshot queued = leased;
            writer.execute(() -> write(queued, reason, id, result));
        } catch (RejectedExecutionException e) {
            finish(id, result, ReplayCapture.refused(closed ? ReplayCapture.Refusal.STOPPED : ReplayCapture.Refusal.WRITER_BUSY), leased);
        } catch (RuntimeException | Error e) {
            finish(id, result, ReplayCapture.refused(ReplayCapture.Refusal.WRITE_FAILED), leased);
            throw e;
        }
    }

    private void finish(long id, CompletableFuture<ReplayCapture> result, ReplayCapture capture, @Nullable LeasedSnapshot leased) {
        if (leased != null) leased.release();
        pendingCaptures.remove(id);
        CapturedReplay written = capture.getReplay();
        if (!result.complete(capture) && written != null) discard(written.getFile().toPath());
    }

    private void write(LeasedSnapshot leased, String reason, long id, CompletableFuture<ReplayCapture> result) {
        if (closed) {
            finish(id, result, ReplayCapture.refused(ReplayCapture.Refusal.STOPPED), leased);
            return;
        }
        RecordingSnapshot snapshot = leased.snapshot();
        String replayId = UUID.randomUUID().toString();
        Path directory = output.directory();
        Path file = directory.resolve(replayId + ReplayFileFormat.FILE_EXTENSION);
        Path partial = directory.resolve(replayId + PARTIAL_EXTENSION);
        try {
            RecorderSettings current = settings;
            ComposeResult composed = composer.composeWithinLimits(new ComposeRequest(replayId, reason, snapshot,
                    current.getWindowSeconds() * 1000L, current.getRadiusBlocks()), new PartialFile(partial));
            Files.move(partial, file, StandardCopyOption.ATOMIC_MOVE);
            finish(id, result, ReplayCapture.written(new CapturedReplay(replayId, file.toFile(), composed.getDurationMs(),
                    composed.getEventCount(), Files.size(file))), leased);
        } catch (ReplayTooLargeException e) {
            logger.warning("[replay] Could not write the replay of " + snapshot.getTargetName() + ": " + e.getMessage());
            discard(partial);
            finish(id, result, ReplayCapture.refused(ReplayCapture.Refusal.TOO_LARGE), leased);
        } catch (IOException | RuntimeException e) {
            logger.log(Level.WARNING, "[replay] Could not write the replay of " + snapshot.getTargetName(), e);
            discard(partial);
            discard(file);
            finish(id, result, ReplayCapture.refused(ReplayCapture.Refusal.WRITE_FAILED), leased);
        } catch (Error e) {
            discard(partial);
            discard(file);
            finish(id, result, ReplayCapture.refused(ReplayCapture.Refusal.WRITE_FAILED), leased);
            throw e;
        }
    }

    private void discard(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException | RuntimeException e) {
            logger.fine("[replay] Could not delete " + file + ": " + e);
        }
    }

    public int activeRecordings() {
        return recordings.size();
    }

    public long usedBytes() {
        return budget.usedBytes();
    }

    public long limitBytes() {
        return budget.limitBytes();
    }

    private void maintain() {
        try {
            long windowMs = settings.getWindowSeconds() * 1000L;
            boolean exceeded = budget.exceeded();
            double keepFraction = budget.keepFraction();
            for (Recording recording : recordings.values()) {
                recording.serial().execute(() -> recording.maintain(windowMs));
                if (exceeded && recording.requestShed()) recording.serial().execute(() -> recording.shedToShare(keepFraction));
            }
        } catch (RuntimeException e) {
            logger.log(Level.FINE, "[replay] Replay maintenance failed", e);
        }
    }

    @Override
    public void close() {
        detach();
        awaitShutdown();
    }

    public void detach() {
        synchronized (admission) {
            closed = true;
        }
        try {
            PacketEvents.getAPI().getEventManager().unregisterListener(tap);
        } finally {
            for (UUID target : new ArrayList<>(recordings.keySet())) stop(target);
            for (Map.Entry<Long, CompletableFuture<ReplayCapture>> pending : new ArrayList<>(pendingCaptures.entrySet())) {
                finish(pending.getKey(), pending.getValue(), ReplayCapture.refused(ReplayCapture.Refusal.STOPPED), null);
            }
            ScheduledFuture<?> task = maintenanceTask;
            if (task != null) task.cancel(false);
            maintenance.shutdownNow();
            ingest.shutdown();
            writer.shutdown();
            deflaters.close();
        }
    }

    public void awaitShutdown() {
        awaitQuietly(ingest);
        awaitQuietly(writer);
        failures.summarize();
    }

    private void awaitQuietly(ThreadPoolExecutor executor) {
        try {
            if (!executor.awaitTermination(SHUTDOWN_DEADLINE_MS, TimeUnit.MILLISECONDS)) executor.shutdownNow();
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private static ThreadFactory threads(String prefix) {
        AtomicInteger counter = new AtomicInteger(1);
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + "-" + counter.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        };
    }

    private final class PartialFile implements ComposeTarget {
        private final Path path;

        private PartialFile(Path path) {
            this.path = path;
        }

        @Override
        public OutputStream open() throws IOException {
            return new BufferedOutputStream(output.create(path));
        }

        @Override
        public void discard() throws IOException {
            Files.deleteIfExists(path);
        }
    }
}
