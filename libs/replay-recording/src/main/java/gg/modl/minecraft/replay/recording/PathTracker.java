package gg.modl.minecraft.replay.recording;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class PathTracker extends ForwardingVisitor {
    private final WorldModel model;
    private final long windowStart;
    private final Map<Integer, Set<Long>> visited = new HashMap<>();
    private final Map<Long, List<PendingChange>> pending = new HashMap<>();
    private boolean started;

    PathTracker(WorldModel model, long windowStart) {
        super(model);
        this.model = model;
        this.windowStart = windowStart;
        model.listen(this::changed);
    }

    Map<Integer, Set<Long>> visited() {
        if (!started) includeCurrent();
        return visited;
    }

    List<PendingChange> pendingFor(long chunkKey) {
        List<PendingChange> changes = pending.get(chunkKey);
        return changes == null ? new ArrayList<>() : changes;
    }

    boolean includes(long chunkKey, int radiusChunks) {
        Set<Long> chunks = visited().get(BlockPositions.chunkSegment(chunkKey));
        if (chunks == null) return false;
        int chunkX = BlockPositions.chunkX(chunkKey);
        int chunkZ = BlockPositions.chunkZ(chunkKey);
        for (Long seen : chunks) {
            if (Math.abs(BlockPositions.chunkX(seen) - chunkX) <= radiusChunks && Math.abs(BlockPositions.chunkZ(seen) - chunkZ) <= radiusChunks) {
                return true;
            }
        }
        return false;
    }

    private void changed(int segment, long time, int x, int y, int z, int state) {
        if (time >= windowStart || segment < 0) return;
        pending.computeIfAbsent(BlockPositions.chunkKey(segment, x >> 4, z >> 4), ignored -> new ArrayList<>())
                .add(new PendingChange(time, x, y, z, state));
    }

    private void includeCurrent() {
        started = true;
        if (model.getSegment() < 0) return;
        ModelEntity self = model.targetEntity();
        visited.computeIfAbsent(model.getSegment(), ignored -> new HashSet<>())
                .add(BlockPositions.chunkKey(model.getSegment(), Fixed.blockOf(self.getX()) >> 4, Fixed.blockOf(self.getZ()) >> 4));
    }

    private void observe(long time) {
        if (time >= windowStart) includeCurrent();
    }

    @Override
    public void targetMove(long time, int mask, long x, long y, long z, float yaw, float pitch, boolean onGround) {
        if (!started) observe(time);
        super.targetMove(time, mask, x, y, z, yaw, pitch, onGround);
        observe(time);
    }

    @Override
    public void worldSegment(long time, int segment, int targetEntityId) {
        if (!started) observe(time);
        super.worldSegment(time, segment, targetEntityId);
        observe(time);
    }
}
