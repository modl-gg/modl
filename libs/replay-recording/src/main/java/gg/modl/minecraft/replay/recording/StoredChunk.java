package gg.modl.minecraft.replay.recording;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

final class StoredChunk {
    static final int MAX_LIGHT_UPDATES = 8;
    static final int MAX_PARTIALS = 16;
    static final int MAX_OVERLAY_ENTRIES = 8192;

    private final RawPacket packet;
    private final int bulkIndex;
    private final long receivedAt;
    private long unloadedAt = -1;
    private LongIntMap overlay = new LongIntMap();
    private final List<TimedPacket> lightUpdates = new ArrayList<>();
    private final List<TimedPacket> partials = new ArrayList<>();

    StoredChunk(RawPacket packet, int bulkIndex, long receivedAt) {
        this.packet = packet;
        this.bulkIndex = bulkIndex;
        this.receivedAt = receivedAt;
    }

    private StoredChunk(StoredChunk source) {
        this.packet = source.packet;
        this.bulkIndex = source.bulkIndex;
        this.receivedAt = source.receivedAt;
        this.unloadedAt = source.unloadedAt;
        this.overlay = source.overlay.copy();
        this.lightUpdates.addAll(source.lightUpdates);
        this.partials.addAll(source.partials);
    }

    StoredChunk snapshot() {
        return new StoredChunk(this);
    }

    RawPacket packet() {
        return packet;
    }

    int bulkIndex() {
        return bulkIndex;
    }

    long receivedAt() {
        return receivedAt;
    }

    long unloadedAt() {
        return unloadedAt;
    }

    void unload(long time) {
        unloadedAt = time;
    }

    void reload() {
        unloadedAt = -1;
    }

    LongIntMap overlay() {
        return overlay;
    }

    boolean overlay(long time, int x, int y, int z, int state) {
        if (time <= receivedAt) return true;
        if (overlay.size() >= MAX_OVERLAY_ENTRIES) return false;
        overlay.put(BlockPositions.pack(x, y, z), state);
        return true;
    }

    List<TimedPacket> lightUpdates() {
        return Collections.unmodifiableList(lightUpdates);
    }

    List<TimedPacket> partials() {
        return Collections.unmodifiableList(partials);
    }

    @Nullable TimedPacket addLightUpdate(TimedPacket update) {
        lightUpdates.add(update);
        return lightUpdates.size() > MAX_LIGHT_UPDATES ? lightUpdates.remove(1) : null;
    }

    boolean addPartial(TimedPacket partial) {
        if (partials.size() >= MAX_PARTIALS) return false;
        partials.add(partial);
        return true;
    }

    List<TimedPacket> lightSince(long time) {
        List<TimedPacket> recent = new ArrayList<>();
        for (TimedPacket update : lightUpdates) {
            if (update.getTime() >= time) recent.add(update);
        }
        return recent;
    }

    long retainedBytes() {
        long bytes = ownBytes();
        for (TimedPacket partial : partials) bytes += partial.getPacket().retainedBytes();
        return bytes;
    }

    long ownBytes() {
        return 96L + overlay.estimatedBytes();
    }

    void pinPackets(Set<Object> pinned) {
        pinned.add(packet);
        for (TimedPacket light : lightUpdates) pinned.add(light.getPacket());
        for (TimedPacket partial : partials) pinned.add(partial.getPacket());
    }
}
