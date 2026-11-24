package gg.modl.minecraft.core.network;

import lombok.Value;

@Value
public class ModerationSnapshot {
    String epoch;
    long revision;
    ModerationState state;
}
