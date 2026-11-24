package gg.modl.minecraft.core.network;

public interface ModerationStateSink {
    void publish(ModerationSnapshot snapshot);
}
