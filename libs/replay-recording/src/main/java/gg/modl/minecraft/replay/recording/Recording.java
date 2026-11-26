package gg.modl.minecraft.replay.recording;

import com.github.retrooper.packetevents.protocol.entity.type.EntityType;
import com.github.retrooper.packetevents.protocol.world.dimension.DimensionType;
import com.github.retrooper.packetevents.util.mappings.IRegistryHolder;
import lombok.Getter;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.IntSupplier;

final class Recording {
    private static final long SLACK_MS = 5_000L;
    private static final long TYPE_ENTRY_BYTES = 64L;
    private static final long ADMIT_SHED_INTERVAL_NANOS = 100_000_000L;
    private static final long COMPOSE_WORKING_BYTES = 1024L * 1024L;

    @Getter private final UUID targetUuid;
    @Getter private final String targetName;
    @Getter private final long startEpochMs;
    private static final AtomicLong STARTS = new AtomicLong();

    private final long startNanos;
    private final long startOrder = STARTS.incrementAndGet();
    private final Journal journal;
    private final ChunkStore chunks;
    private final SerialExecutor serial;
    private final Map<Integer, WorldSegment> segments = new ConcurrentHashMap<>();
    private final Map<Integer, EntityType> entityTypes = new ConcurrentHashMap<>();
    private final Object segmentLock = new Object();
    private WorldModel baseline;
    private volatile int currentSegment = -1;
    private volatile int targetEntityId;
    private volatile boolean hardcore;
    private volatile int targetChunkX;
    private volatile int targetChunkZ;
    private volatile boolean truncated;
    private volatile boolean closed;
    private volatile float rain;
    private volatile float thunder;
    private final AtomicBoolean shedding = new AtomicBoolean();
    private final RecordingMemory memory;
    private final MemoryBudget budget;
    private final IntSupplier radiusChunks;
    private volatile long modelBytes;
    private long lastAdmitShedNanos = System.nanoTime() - ADMIT_SHED_INTERVAL_NANOS;
    private volatile long lastTimeOfDayAt = -1;
    private volatile long lastTimeOfDay;
    private volatile @Nullable IRegistryHolder registries;

    private RecordingCause cause;
    private RecordingCause rank;
    private boolean ending;

    Recording(UUID targetUuid, String targetName, RecordingCause cause, int playerType, MemoryBudget budget, Executor pool,
              IntSupplier radiusChunks, Consumer<RuntimeException> failures) {
        this.targetUuid = targetUuid;
        this.targetName = targetName;
        this.cause = cause;
        this.rank = cause;
        this.startEpochMs = System.currentTimeMillis();
        this.startNanos = System.nanoTime();
        this.memory = new RecordingMemory(budget);
        this.journal = new Journal(memory);
        this.chunks = new ChunkStore(memory);
        this.serial = new SerialExecutor(pool, failures);
        this.budget = budget;
        this.radiusChunks = radiusChunks;
        this.baseline = new WorldModel(playerType, targetUuid, -1, 0);
    }

    synchronized RecordingCause cause() {
        return cause;
    }

    boolean startedBefore(Recording other) {
        return startOrder < other.startOrder;
    }

    synchronized RecordingCause rank() {
        return rank;
    }

    synchronized void raise(RecordingCause requested) {
        if (requested.outranks(rank)) rank = requested;
    }

    synchronized boolean keepForFlag() {
        if (ending) return false;
        cause = RecordingCause.ANTICHEAT_FLAG;
        raise(RecordingCause.ANTICHEAT_FLAG);
        return true;
    }

    synchronized boolean endIfStartedBy(RecordingCause starter) {
        if (ending || cause != starter) return false;
        ending = true;
        return true;
    }

    long now() {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    void append(RecordWriter record, long time) {
        if (!closed) journal.append(record, time);
    }

    Executor serial() {
        return serial;
    }

    int currentSegment() {
        return currentSegment;
    }

    int targetEntityId() {
        return targetEntityId;
    }

    boolean hardcore() {
        return hardcore;
    }

    void hardcore(boolean value) {
        hardcore = value;
    }

    void targetChunk(int chunkX, int chunkZ) {
        targetChunkX = chunkX;
        targetChunkZ = chunkZ;
    }

    boolean shouldRecordTime(long time, long timeOfDay, long intervalMs, long jumpTicks) {
        long previous = lastTimeOfDay;
        boolean first = lastTimeOfDayAt < 0;
        boolean jumped = Math.abs(timeOfDay - previous) > jumpTicks;
        boolean due = time - lastTimeOfDayAt >= intervalMs && timeOfDay != previous;
        if (!first && !jumped && !due) return false;
        lastTimeOfDayAt = time;
        lastTimeOfDay = timeOfDay;
        return true;
    }

    void registries(@Nullable IRegistryHolder holder) {
        if (registries == null && holder != null) registries = holder;
    }

    void markTruncated() {
        truncated = true;
    }

    boolean closed() {
        return closed;
    }

    void trackType(int entityId, EntityType type) {
        if (entityTypes.size() < 65536) entityTypes.put(entityId, type);
    }

    @Nullable EntityType typeOf(int entityId) {
        return entityTypes.get(entityId);
    }

    void forgetType(int entityId) {
        entityTypes.remove(entityId);
    }

    void forgetAllTypes() {
        entityTypes.clear();
    }

    void enterWorld(String key, String worldName, int minY, int height, boolean skyLight,
                    @Nullable DimensionType dimensionType, int entityId) {
        long time = now();
        int segmentId;
        synchronized (segmentLock) {
            WorldSegment current = segments.get(currentSegment);
            if (current != null && current.sameWorldAs(key, worldName) && current.getMinY() == minY && current.getHeight() == height) {
                segmentId = current.getId();
            } else {
                segmentId = currentSegment + 1;
                segments.put(segmentId, new WorldSegment(segmentId, key, worldName, minY, height, skyLight, dimensionType, time));
            }
            currentSegment = segmentId;
            targetEntityId = entityId;
            forgetAllTypes();
            append(RecordEncoder.worldSegment(time, segmentId, entityId), time);
        }
    }

    @Nullable WorldSegment segment(int id) {
        return segments.get(id);
    }

    void storeChunk(int chunkX, int chunkZ, RawPacket packet, int bulkIndex, int segment, long time) {
        chunks.store(segment, chunkX, chunkZ, packet, bulkIndex, time);
        admit();
    }

    void storePartial(int chunkX, int chunkZ, RawPacket packet, int segment, long time) {
        if (!chunks.storePartial(segment, chunkX, chunkZ, packet, time)) truncated = true;
        admit();
    }

    void storeLight(int chunkX, int chunkZ, RawPacket packet, int segment, long time) {
        chunks.storeLight(segment, chunkX, chunkZ, packet, time);
    }

    float rain() {
        return rain;
    }

    float thunder() {
        return thunder;
    }

    void weather(float newRain, float newThunder) {
        rain = newRain;
        thunder = newThunder;
    }

    private void admit() {
        long nowNanos = System.nanoTime();
        if (memory.hasLeases() || !budget.exceeded() || nowNanos - lastAdmitShedNanos < ADMIT_SHED_INTERVAL_NANOS) return;
        lastAdmitShedNanos = nowNanos;
        shedTo((long) (retainedBytes() * budget.keepFraction()));
    }

    void unloadChunk(int chunkX, int chunkZ, int segment, long time) {
        chunks.unload(segment, chunkX, chunkZ, time);
    }

    void maintain(long windowMs) {
        long cutoff = now() - windowMs - SLACK_MS;
        WorldModel model = baseline;
        RecordVisitor folding = new FoldingVisitor(model, chunks);
        for (JournalBlock block : journal.takeClosedOlderThan(cutoff)) block.replay(folding);
        int oldest = model.getSegment();
        chunks.evict(cutoff, oldest, now());
        segments.keySet().removeIf(id -> id < oldest);
        estimateModel();
    }

    private void estimateModel() {
        long estimate = closed ? 0 : baseline.estimatedBytes() + entityTypes.size() * TYPE_ENTRY_BYTES;
        memory.adjust(estimate - modelBytes);
        modelBytes = estimate;
    }

    boolean requestShed() {
        return shedding.compareAndSet(false, true);
    }

    void shedToShare(double keepFraction) {
        try {
            if (!memory.hasLeases()) shedTo((long) (retainedBytes() * keepFraction));
        } finally {
            shedding.set(false);
        }
    }

    void shedTo(long targetBytes) {
        int segment = currentSegment;
        int chunkX = targetChunkX;
        int chunkZ = targetChunkZ;
        if (chunks.shedFarthest(segment, chunkX, chunkZ, radiusChunks.getAsInt(), retainedBytes() - targetBytes) > 0) truncated = true;
        while (retainedBytes() > targetBytes) {
            JournalBlock oldest = journal.takeOldestClosed();
            if (oldest == null) break;
            oldest.replay(new FoldingVisitor(baseline, chunks));
            truncated = true;
        }
        if (chunks.shedFarthest(segment, chunkX, chunkZ, -1, retainedBytes() - targetBytes) > 0) truncated = true;
    }

    long retainedBytes() {
        return journal.reservedBytes() + chunks.retainedBytes() + modelBytes;
    }

    RecordingSnapshot snapshot(long captureTime) {
        return snapshot(captureTime, RecordingMemory.identitySet());
    }

    @Nullable LeasedSnapshot leaseSnapshot(long captureTime) {
        Set<Object> pinned = RecordingMemory.identitySet();
        RecordingSnapshot snapshot = snapshot(captureTime, pinned);
        long exclusiveBytes = COMPOSE_WORKING_BYTES + modelBytes;
        for (JournalBlock block : snapshot.getBlocks()) {
            if (!pinned.contains(block)) exclusiveBytes += block.capacity();
        }
        for (StoredChunk chunk : snapshot.getChunks().values()) exclusiveBytes += chunk.ownBytes();
        RecordingMemory.Lease lease = memory.lease(pinned, exclusiveBytes);
        return lease == null ? null : new LeasedSnapshot(snapshot, memory, lease);
    }

    private RecordingSnapshot snapshot(long captureTime, Set<Object> pinned) {
        List<WorldSegment> knownSegments = new ArrayList<>(segments.values());
        return new RecordingSnapshot(targetUuid, targetName, startEpochMs, captureTime, baseline.copy(), journal.snapshot(pinned),
                chunks.snapshot(pinned), knownSegments, hardcore, truncated || chunks.truncated(), registries);
    }

    void close() {
        closed = true;
        serial.execute(() -> {
            journal.clear();
            chunks.clear();
            estimateModel();
        });
    }

}
