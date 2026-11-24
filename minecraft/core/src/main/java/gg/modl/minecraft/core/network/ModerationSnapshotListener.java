package gg.modl.minecraft.core.network;

public interface ModerationSnapshotListener {
    void accept(String sourceId, ModerationSnapshot snapshot);

    void retire(String sourceId);

    void linkUp();

    void linkDown();
}
