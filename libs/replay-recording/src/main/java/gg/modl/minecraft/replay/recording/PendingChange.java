package gg.modl.minecraft.replay.recording;

import lombok.Value;

@Value
class PendingChange {
    long time;
    int x;
    int y;
    int z;
    int state;
}
